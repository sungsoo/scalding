/*
Copyright 2012 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.twitter.scalding

import cascading.tap._
import cascading.scheme._
import cascading.pipe._
import cascading.pipe.assembly._
import cascading.pipe.joiner._
import cascading.flow._
import cascading.operation._
import cascading.operation.aggregator._
import cascading.operation.filter._
import cascading.tuple._
import cascading.cascade._
import cascading.operation.Debug.Output

import scala.util.Random

import java.util.concurrent.atomic.AtomicInteger

object RichPipe extends java.io.Serializable {
  private val nextPipe = new AtomicInteger(-1)

  def apply(p: Pipe): RichPipe = new RichPipe(p)

  implicit def toPipe(rp: RichPipe): Pipe = rp.pipe

  def getNextName: String = "_pipe_" + nextPipe.incrementAndGet.toString

  def assignName(p : Pipe) = new Pipe(getNextName, p)

  private val REDUCER_KEY = "mapred.reduce.tasks"
  /**
   * Gets the underlying config for this pipe and sets the number of reducers
   * useful for cascading GroupBy/CoGroup pipes.
   */
  def setReducers(p : Pipe, reducers : Int) : Pipe = {
    if(reducers > 0) {
      p.getStepConfigDef()
        .setProperty(REDUCER_KEY, reducers.toString)
    } else if(reducers != -1) {
      throw new IllegalArgumentException("Number of reducers must be non-negative")
    }
    p
  }
}

/** This is an enrichment-pattern class for cascading.pipe.Pipe.
 * The rule is to never use this class directly in input or return types, but
 * only to add methods to Pipe.
 */
class RichPipe(val pipe : Pipe) extends java.io.Serializable with JoinAlgorithms {
  // We need this for the implicits
  import Dsl._
  import RichPipe.assignName

  /**
   * Rename the current pipe
   */
  def name(s: String): Pipe = new Pipe(s, pipe)

  /**
   * Beginning of block with access to expensive nonserializable state. The state object should
   * contain a function release() for resource management purpose.
   */
  def using[C <: { def release() }](bf: => C) = new {

    /**
     * For pure side effect.
     */
    def foreach[A](f: Fields)(fn: (C, A) => Unit)
            (implicit conv: TupleConverter[A], set: TupleSetter[Unit], flowDef: FlowDef, mode: Mode) = {
      conv.assertArityMatches(f)
      val newPipe = new Each(pipe, f, new SideEffectMapFunction(bf, fn,
        new Function1[C, Unit] with java.io.Serializable {
          def apply(c: C) { c.release() }
        },
        Fields.NONE, conv, set))
      NullSource.writeFrom(newPipe)(flowDef, mode)
      newPipe
    }

    /**
     * map with state
     */
    def map[A,T](fs: (Fields,Fields))(fn: (C, A) => T)
                (implicit conv: TupleConverter[A], set: TupleSetter[T]) = {
      conv.assertArityMatches(fs._1)
      set.assertArityMatches(fs._2)
      val mf = new SideEffectMapFunction(bf, fn,
        new Function1[C, Unit] with java.io.Serializable {
          def apply(c: C) { c.release() }
        },
        fs._2, conv, set)
      new Each(pipe, fs._1, mf, defaultMode(fs._1, fs._2))
    }

    /**
     * flatMap with state
     */
    def flatMap[A,T](fs: (Fields,Fields))(fn: (C, A) => TraversableOnce[T])
                (implicit conv: TupleConverter[A], set: TupleSetter[T]) = {
      conv.assertArityMatches(fs._1)
      set.assertArityMatches(fs._2)
      val mf = new SideEffectFlatMapFunction(bf, fn,
        new Function1[C, Unit] with java.io.Serializable {
          def apply(c: C) { c.release() }
        },
        fs._2, conv, set)
      new Each(pipe, fs._1, mf, defaultMode(fs._1, fs._2))
    }
  }

  /**
   * Keep only the given fields, and discard the rest.
   * takes any number of parameters as long as we can convert
   * them to a fields object
   */
  def project(fields : Fields): Pipe =
    new Each(pipe, fields, new Identity(fields))

  /**
   * Discard the given fields, and keep the rest.
   * Kind of the opposite of project method.
   */
  def discard(f : Fields): Pipe =
    new Each(pipe, f, new NoOp, Fields.SWAP)

  /**
   * Insert a function into the pipeline:
   */
  def thenDo[T,U](pfn : (T) => U)(implicit in : (RichPipe)=>T): U = pfn(in(this))

  /**
   * group the Pipe based on fields
   *
   * builder is typically a block that modifies the given GroupBuilder
   * the final OUTPUT of the block is used to schedule the new pipe
   * each method in GroupBuilder returns this, so it is recommended
   * to chain them and use the default input:
   *
   * {{{
   *   _.size.max('f1) etc...
   * }}}
   */
  def groupBy(f: Fields)(builder: GroupBuilder => GroupBuilder): Pipe =
    builder(new GroupBuilder(f)).schedule(pipe.getName, pipe)

  /**
   * Returns the set of distinct tuples containing the specified fields
   */
  def distinct(f: Fields): Pipe =
    groupBy(f) { _.size('__uniquecount__) }.project(f)

  /**
   * Returns the set of unique tuples containing the specified fields. Same as distinct
   */
  def unique(f : Fields) : Pipe = distinct(f)

  /**
   * Merge or Concatenate several pipes together with this one:
   */
  def ++(that : Pipe): Pipe = {
    if(this.pipe == that) {
      // Cascading fails on self merge:
      // solution by Jack Guo
      new Merge(assignName(this.pipe), assignName(new Each(that, new Identity)))
    }
    else {
      new Merge(assignName(this.pipe), assignName(that))
    }
  }

  /**
   * Group all tuples down to one reducer.
   * (due to cascading limitation).
   * This is probably only useful just before setting a tail such as Database
   * tail, so that only one reducer talks to the DB.  Kind of a hack.
   */
  def groupAll: Pipe = groupAll { _.pass }

  /**
   * == Warning ==
   * This kills parallelism. All the work is sent to one reducer.
   *
   * Only use this in the case that you truly need all the data on one
   * reducer.
   *
   * Just about the only reasonable case of this method is to reduce all values of a column
   * or count all the rows.
   */
  def groupAll(gs : GroupBuilder => GroupBuilder) =
    map(()->'__groupAll__) { (u:Unit) => 1 }
    .groupBy('__groupAll__) { gs(_).reducers(1) }
    .discard('__groupAll__)

  /**
   * Force a random shuffle of all the data to exactly n reducers
   */
  def shard(n: Int): Pipe = groupRandomly(n) { _.pass }
  /**
   * Force a random shuffle of all the data to exactly n reducers,
   * with a given seed if you need repeatability.
   */
  def shard(n : Int, seed : Int) : Pipe = groupRandomly(n, seed) { _.pass }

  /**
   * Like groupAll, but randomly groups data into n reducers.
   *
   * you can provide a seed for the random number generator
   * to get reproducible results
   */
  def groupRandomly(n : Int)(gs : GroupBuilder => GroupBuilder) : Pipe =
    groupRandomlyAux(n, None)(gs)

  /**
   * like groupRandomly(n : Int) with a given seed in the randomization
   */
  def groupRandomly(n : Int, seed : Long)(gs : GroupBuilder => GroupBuilder) : Pipe =
    groupRandomlyAux(n, Some(seed))(gs)

  // achieves the behavior that reducer i gets i_th shard
  // by relying on cascading to use java's hashCode, which hash ints
  // to themselves
  protected def groupRandomlyAux(n : Int, optSeed : Option[Long])(gs : GroupBuilder => GroupBuilder) : Pipe = {
    using(statefulRandom(optSeed))
      .map(()->'__shard__) { (r:Random, _:Unit) => r.nextInt(n) }
      .groupBy('__shard__) { gs(_).reducers(n) }
      .discard('__shard__)
  }

  private def statefulRandom(optSeed : Option[Long]) : Random with Stateful = {
    val random = new Random with Stateful
    if (optSeed.isDefined) { random.setSeed(optSeed.get) }
    random
  }

  /**
   * Put all rows in random order
   *
   * you can provide a seed for the random number generator
   * to get reproducible results
   */
  def shuffle(shards : Int) : Pipe = groupAndShuffleRandomly(shards) { _.pass }
  def shuffle(shards : Int, seed : Long) : Pipe = groupAndShuffleRandomly(shards, seed) { _.pass }

  /**
   * Like shard, except do some operation im the reducers
   */
  def groupAndShuffleRandomly(reducers : Int)(gs : GroupBuilder => GroupBuilder) : Pipe =
    groupAndShuffleRandomlyAux(reducers, None)(gs)

  /**
   * Like groupAndShuffleRandomly(reducers : Int) but with a fixed seed.
   */
  def groupAndShuffleRandomly(reducers : Int, seed : Long)
    (gs : GroupBuilder => GroupBuilder) : Pipe =
    groupAndShuffleRandomlyAux(reducers, Some(seed))(gs)

  private def groupAndShuffleRandomlyAux(reducers : Int, optSeed : Option[Long])
    (gs : GroupBuilder => GroupBuilder) : Pipe = {
    using(statefulRandom(optSeed))
      .map(()->('__shuffle__)) { (r:Random, _:Unit) => r.nextDouble() }
      .groupRandomlyAux(reducers, optSeed){ g : GroupBuilder =>
        gs(g.sortBy('__shuffle__))
      }
      .discard('__shuffle__)
  }

  /**
   * Adds a field with a constant value.
   *
   * == Usage ==
   * {{{
   * insert('a, 1)
   * }}}
   */
  def insert[A: Manifest: TupleSetter](fs: Fields, value: A): Pipe = {
    implicit val unit = implicitly[TupleConverter[Unit]]
    map[Unit,A](() -> fs) { _:Unit => value }
  }


  /**
   * Rename some set of N fields as another set of N fields
   *
   * == Usage ==
   * {{{
   * rename('x -> 'z)
   *        rename(('x,'y) -> ('X,'Y))
   * }}}
   *
   * == Warning ==
   * `rename('x,'y)` is interpreted by scala as `rename(Tuple2('x,'y))`
   * which then does `rename('x -> 'y)`.  This is probably not what is intended
   * but the compiler doesn't resolve the ambiguity.  YOU MUST CALL THIS WITH
   * A TUPLE2!  If you don't, expect the unexpected.
   */
  def rename(fields : (Fields,Fields)) : Pipe = {
    val (fromFields, toFields) = fields
    val in_arity = fromFields.size
    val out_arity = toFields.size
    assert(in_arity == out_arity, "Number of field names must match for rename")
    new Each(pipe, fromFields, new Identity( toFields ), Fields.SWAP)
  }

  /**
   * Keep only items that satisfy this predicate.
   */
  def filter[A](f : Fields)(fn : (A) => Boolean)
      (implicit conv : TupleConverter[A]) : Pipe = {
    conv.assertArityMatches(f)
    new Each(pipe, f, new FilterFunction(fn, conv))
  }

  /**
   * Keep only items that don't satisfy this predicate.
   * `filterNot` is equal to negating a `filter` operation.
   *
   * {{{ filterNot('name) { name: String => name contains "a" } }}}
   *
   * is the same as:
   *
   * {{{ filter('name) { name: String => !(name contains "a") } }}}
   */
  def filterNot[A](f : Fields)(fn : (A) => Boolean)
      (implicit conv : TupleConverter[A]) : Pipe =
    filter[A](f)(!fn(_))

  /**
   * Text files can have corrupted data. If you use this function and a
   * cascading trap you can filter out corrupted data from your pipe.
   */
  def verifyTypes[A](f: Fields)(implicit conv: TupleConverter[A]): Pipe  = {
    pipe.filter(f) { (a: A) => true }
  }

  /**
   * Given a function, partitions the pipe into several groups based on the
   * output of the function. Then applies a GroupBuilder function on each of the
   * groups.
   *
   * Example:
      pipe
        .mapTo(()->('age, 'weight) { ... }
        .partition('age -> 'isAdult) { _ > 18 } { _.average('weight) }
   * pipe now contains the average weights of adults and minors.
   */
  def partition[A,R](fs: (Fields, Fields))(fn: (A) => R)(
    builder: GroupBuilder => GroupBuilder)(
    implicit rManifest: Manifest[R],
             conv: TupleConverter[A],
             ord: Ordering[R],
             rset: TupleSetter[R]): Pipe = {
    val (fromFields, toFields) = fs
    conv.assertArityMatches(fromFields)
    rset.assertArityMatches(toFields)

    val tmpFields = new Fields("__temp__")
    tmpFields.setComparator("__temp__", ord)

    map(fromFields -> tmpFields)(fn)(rManifest, conv, TupleSetter.singleSetter[R])
      .groupBy(tmpFields)(builder)
      .map[R,R](tmpFields -> toFields){ (r:R) => r }(rManifest, TupleConverter.singleConverter[R], rset)
      .discard(tmpFields)
  }

  /**
   * If you use a map function that does not accept TupleEntry args,
   * which is the common case, an implicit conversion in GeneratedConversions
   * will convert your function into a `(TupleEntry => T)`.  The result type
   * T is converted to a cascading Tuple by an implicit `TupleSetter[T]`.
   * acceptable T types are primitive types, cascading Tuples of those types,
   * or `scala.Tuple(1-22)` of those types.
   *
   * After the map, the input arguments will be set to the output of the map,
   * so following with filter or map is fine without a new using statement if
   * you mean to operate on the output.
   *
   * {{{
   * map('data -> 'stuff)
   * }}}
   *
   * * if output equals input, REPLACE is used.
   * * if output or input is a subset of the other SWAP is used.
   * * otherwise we append the new fields (cascading Fields.ALL is used)
   *
   * {{{
   * mapTo('data -> 'stuff)
   * }}}
   *
   *  Only the results (stuff) are kept (cascading Fields.RESULTS)
   *
   * == Note ==
   * Using mapTo is the same as using map followed by a project for
   * selecting just the ouput fields
   */
  def map[A,T](fs: (Fields,Fields))(fn: A => T)
        (implicit tManifest: Manifest[T], conv: TupleConverter[A], setter: TupleSetter[T]): Pipe = {
      conv.assertArityMatches(fs._1)
      setter.assertArityMatches(fs._2)
      each(fs)(new MapFunction[A,T](fn, _, conv, setter))
  }
  def mapTo[A,T](fs: (Fields,Fields))(fn: A => T)
        (implicit tManifest: Manifest[T], conv: TupleConverter[A], setter: TupleSetter[T]): Pipe = {
      conv.assertArityMatches(fs._1)
      setter.assertArityMatches(fs._2)
      eachTo(fs)(new MapFunction[A,T](fn, _, conv, setter))
  }
  def flatMap[A,T](fs: (Fields,Fields))(fn: A => TraversableOnce[T])
        (implicit tManifest: Manifest[T], conv: TupleConverter[A], setter: TupleSetter[T]): Pipe = {
      conv.assertArityMatches(fs._1)
      setter.assertArityMatches(fs._2)
      each(fs)(new FlatMapFunction[A,T](fn, _, conv, setter))
  }
  def flatMapTo[A,T](fs: (Fields,Fields))(fn: A => TraversableOnce[T])
        (implicit tManifest: Manifest[T], conv: TupleConverter[A], setter: TupleSetter[T]): Pipe = {
      conv.assertArityMatches(fs._1)
      setter.assertArityMatches(fs._2)
      eachTo(fs)(new FlatMapFunction[A,T](fn, _, conv, setter))
  }

  /**
   * Filters all data that is defined for this partial function and then applies that function
   */
  def collect[A,T](fs : (Fields,Fields))(fn : PartialFunction[A,T])
                (implicit conv : TupleConverter[A], setter : TupleSetter[T]) : Pipe = {
      conv.assertArityMatches(fs._1)
      setter.assertArityMatches(fs._2)
      pipe.each(fs)(new CollectFunction[A,T](fn, _, conv, setter))
  }
  def collectTo[A,T](fs : (Fields,Fields))(fn : PartialFunction[A,T])
                (implicit conv : TupleConverter[A], setter : TupleSetter[T]) : Pipe = {
      conv.assertArityMatches(fs._1)
      setter.assertArityMatches(fs._2)
      pipe.eachTo(fs)(new CollectFunction[A,T](fn, _, conv, setter))
  }

  /**
   * the same as
   *
   * {{{
   * flatMap(fs) { it : TraversableOnce[T] => it }
   * }}}
   *
   * Common enough to be useful.
   */
  def flatten[T](fs: (Fields, Fields))
    (implicit tManifest: Manifest[T], conv: TupleConverter[TraversableOnce[T]], setter: TupleSetter[T]): Pipe =
    flatMap[TraversableOnce[T],T](fs)({ it : TraversableOnce[T] => it })(tManifest, conv, setter)

  /**
   * the same as
   *
   * {{{
   * flatMapTo(fs) { it : TraversableOnce[T] => it }
   * }}}
   *
   * Common enough to be useful.
   */
  def flattenTo[T](fs : (Fields, Fields))
    (implicit tManifest: Manifest[T], conv : TupleConverter[TraversableOnce[T]], setter : TupleSetter[T]): Pipe =
    flatMapTo[TraversableOnce[T],T](fs)({ it : TraversableOnce[T] => it })(tManifest, conv, setter)

  /**
   * Force a materialization to disk in the flow.
   * This is useful before crossWithTiny if you filter just before. Ideally scalding/cascading would
   * see this (and may in future versions), but for now it is here to aid in hand-tuning jobs
   */
  lazy val forceToDisk: Pipe = new Checkpoint(pipe)

  /**
   * Convenience method for integrating with existing cascading Functions
   */
  def each(fs : (Fields,Fields))(fn : Fields => Function[_]) = {
    new Each(pipe, fs._1, fn(fs._2), defaultMode(fs._1, fs._2))
  }

  /**
   * Same as above, but only keep the results field.
   */
  def eachTo(fs : (Fields,Fields))(fn : Fields => Function[_]) = {
    new Each(pipe, fs._1, fn(fs._2), Fields.RESULTS)
  }

  /**
   * This is an analog of the SQL/Excel unpivot function which converts columns of data
   * into rows of data.  Only the columns given as input fields are expanded in this way.
   * For this operation to be reversible, you need to keep some unique key on each row.
   * See GroupBuilder.pivot to reverse this operation assuming you leave behind a grouping key
   * == Example ==
   * {{{
   * pipe.unpivot(('w,'x,'y,'z) -> ('feature, 'value))
   * }}}
   *
   * takes rows like:
   * {{{
   * key, w, x, y, z
   * 1, 2, 3, 4, 5
   * 2, 8, 7, 6, 5
   * }}}
   * to:
   * {{{
   * key, feature, value
   * 1, w, 2
   * 1, x, 3
   * 1, y, 4
   * }}}
   * etc...
   */
  def unpivot(fieldDef : (Fields,Fields)) : Pipe = {
    assert(fieldDef._2.size == 2, "Must specify exactly two Field names for the results")
    // toKeyValueList comes from TupleConversions
    pipe.flatMap(fieldDef) { te: TupleEntry => TupleConverter.KeyValueList(te) }
      .discard(fieldDef._1)
  }

  /**
   * Keep at most n elements.  This is implemented by keeping
   * approximately n/k elements on each of the k mappers or reducers (whichever we wind
   * up being scheduled on).
   */
  def limit(n : Long) : Pipe = new Each(pipe, new Limit(n))

   /**
   * Sample percent of elements. percent should be between 0.00 (0%) and 1.00 (100%)
   * you can provide a seed to get reproducible results
   *
   */
  def sample(percent : Double) : Pipe = new Each(pipe, new Sample(percent))
  def sample(percent : Double, seed : Long) : Pipe = new Each(pipe, new Sample(seed, percent))

  /**
   * Sample percent of elements with return. percent should be between 0.00 (0%) and 1.00 (100%)
   * you can provide a seed to get reproducible results
   *
   */
   def sampleWithReplacement(percent : Double) : Pipe = new Each(pipe, new SampleWithReplacement(percent), Fields.ALL)
   def sampleWithReplacement(percent : Double, seed : Int) : Pipe = new Each(pipe, new SampleWithReplacement(percent, seed), Fields.ALL)

  /**
   * Print all the tuples that pass to stderr
   */
  def debug: Pipe = debug(PipeDebug())

  /**
   *  Print the tuples that pass with the options configured in debugger
   * For instance:
   *   {{{ debug(PipeDebug().toStdOut.printTuplesEvery(100)) }}}
   */
  def debug(dbg: PipeDebug): Pipe = dbg(pipe)

  /**
   * Write all the tuples to the given source and return this Pipe
   */
  def write(outsource : Source)(implicit flowDef : FlowDef, mode : Mode) = {
    outsource.writeFrom(pipe)(flowDef, mode)
    pipe
  }

  /**
   * Adds a trap to the current pipe,
   * which will capture all exceptions that occur in this pipe
   * and save them to the trapsource given
   *
   * Traps do not include the original fields in a tuple,
   * only the fields seen in an operation.
   * Traps also do not include any exception information.
   *
   * There can only be at most one trap for each pipe.
  **/
  def addTrap(trapsource : Source)(implicit flowDef : FlowDef, mode : Mode) = {
    flowDef.addTrap(pipe, trapsource.createTap(Write)(mode))
    pipe
  }

  /**
   * Divides sum of values for this variable by their sum; assumes without checking that division is supported
   * on this type and that sum is not zero
   *
   * If those assumptions do not hold, will throw an exception -- consider checking sum sepsarately and/or using addTrap
   *
   * in some cases, crossWithTiny has been broken, the implementation supports a work-around
   */
  def normalize(f : Fields, useTiny : Boolean = true) : Pipe = {
    val total = groupAll { _.sum[Double](f -> '__total_for_normalize__) }
    (if(useTiny) {
      crossWithTiny(total)
    } else {
      crossWithSmaller(total)
    })
    .map(Fields.merge(f, '__total_for_normalize__) -> f) { args : (Double, Double) =>
      args._1 / args._2
    }
  }

  /** Maps the input fields into an output field of type T. For example:
   *
   * {{{
   *   pipe.pack[(Int, Int)] (('field1, 'field2) -> 'field3)
   * }}}
   *
   * will pack fields 'field1 and 'field2 to field 'field3, as long as 'field1 and 'field2
   * can be cast into integers. The output field 'field3 will be of tupel `(Int, Int)`
   *
   */
  def pack[T: Manifest: TuplePacker: TupleSetter](fs : (Fields, Fields)): Pipe = {
    val (fromFields, toFields) = fs
    assert(toFields.size == 1, "Can only output 1 field in pack")
    implicit val conv = implicitly[TuplePacker[T]].newConverter(fromFields)
    pipe.map(fs) { input : T => input }
  }

  /**
   * Same as pack but only the to fields are preserved.
   */
  def packTo[T: Manifest: TuplePacker: TupleSetter](fs : (Fields, Fields)): Pipe = {
    val (fromFields, toFields) = fs
    assert(toFields.size == 1, "Can only output 1 field in pack")
    implicit val conv = implicitly[TuplePacker[T]].newConverter(fromFields)
    pipe.mapTo(fs) { input : T => input }
  }

  /**
   * The opposite of pack. Unpacks the input field of type `T` into
   * the output fields. For example:
   *
   * {{{
   *   pipe.unpack[(Int, Int)] ('field1 -> ('field2, 'field3))
   * }}}
   *
   * will unpack 'field1 into 'field2 and 'field3
   */
  def unpack[T: Manifest: TupleUnpacker: TupleConverter](fs : (Fields, Fields)): Pipe = {
    val (fromFields, toFields) = fs
    assert(fromFields.size == 1, "Can only take 1 input field in unpack")
    val unpacker = implicitly[TupleUnpacker[T]]
    val fields = (fromFields, unpacker.getResultFields(toFields))
    implicit val setter = unpacker.newSetter(toFields)
    pipe.map(fields) { input : T => input }
  }

  /**
   * Same as unpack but only the to fields are preserved.
   */
  def unpackTo[T: Manifest: TupleUnpacker: TupleConverter](fs : (Fields, Fields)): Pipe = {
    val (fromFields, toFields) = fs
    assert(fromFields.size == 1, "Can only take 1 input field in unpack")
    val unpacker = implicitly[TupleUnpacker[T]]
    val fields = (fromFields, unpacker.getResultFields(toFields))
    implicit val setter = unpacker.newSetter(toFields)
    pipe.mapTo(fields) { input : T => input }
  }
}

/**
 * A simple trait for releasable resource. Provides noop implementation.
 */
trait Stateful {
  def release() {}
}
