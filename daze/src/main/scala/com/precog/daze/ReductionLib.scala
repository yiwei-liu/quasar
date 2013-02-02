/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog
package daze

import bytecode._

import yggdrasil._
import yggdrasil.table._

import com.precog.util._

import scalaz._
import scalaz.std.anyVal._
import scalaz.std.option._
import scalaz.std.set._
import scalaz.std.tuple._
import scalaz.syntax.foldable._
import scalaz.syntax.std.option._
import scalaz.syntax.std.boolean._

import scala.annotation.tailrec
import scala.collection.mutable

class LongAdder {
  var t = 0L
  val ts = mutable.ArrayBuffer.empty[BigDecimal]

  final def maxLongSqrt = 3037000499L

  def add(x: BigDecimal): Unit = ts.append(x)

  def addSquare(x: Long) = if (x < maxLongSqrt)
    add(x * x)
  else
    add(BigDecimal(x) pow 2)

  def add(x: Long): Unit = {
    val y = t + x
    if ((~(x ^ t) & (x ^ y)) >= 0L) {
      t = y
    } else {
      ts.append(BigDecimal(t))
      t = x
    }
  }
  def total(): BigDecimal = ts.sum + t
}

trait ReductionLibModule[M[+_]] extends ColumnarTableLibModule[M] {
  trait ReductionLib extends ColumnarTableLib {
    import BigDecimalOperations._
    val ReductionNamespace = Vector()

    override def _libReduction = super._libReduction ++ Set(Count, Max, Min, Sum, Mean, GeometricMean, SumSq, Variance, StdDev, Forall, Exists)

    val CountMonoid = implicitly[Monoid[Count.Result]]
    object Count extends Reduction(ReductionNamespace, "count") {
      // limiting ourselves to 9.2e18 rows doesn't seem like a problem.
      type Result = Long
      
      implicit val monoid = CountMonoid

      val tpe = UnaryOperationType(JType.JUnfixedT, JNumberT)
      
      def reducer(ctx: EvaluationContext): Reducer[Result] = new CReducer[Result] {
        def reduce(schema: CSchema, range: Range) = {
          val cx = schema.columns(JType.JUnfixedT).toArray
          var count = 0L
          RangeUtil.loop(range) { i =>
            if (Column.isDefinedAt(cx, i)) count += 1L
          }
          count
        }
      }

      def extract(res: Result): Table = Table.constDecimal(Set(CNum(res)))

      def extractValue(res: Result) = Some(CNum(res))
    }

    object Max extends Reduction(ReductionNamespace, "max") {
      type Result = Option[BigDecimal]

      implicit val monoid = new Monoid[Result] {
        def zero = None
        def append(left: Result, right: => Result): Result = {
          (for (l <- left; r <- right) yield l max r) orElse left orElse right
        }
      }

      val tpe = UnaryOperationType(JNumberT, JNumberT)
      
      def reducer(ctx: EvaluationContext): Reducer[Result] = new CReducer[Result] {
        def reduce(schema: CSchema, range: Range): Result = {
          val maxs = schema.columns(JNumberT) map {
            case col: LongColumn =>
              // for longs, we'll use a Boolean to track whether zmax was really
              // seen or not.
              var zmax = Long.MinValue
              val seen = RangeUtil.loopDefined(range, col) { i =>
                val z = col(i)
                if (z > zmax) zmax = z
              }
              if (seen) Some(BigDecimal(zmax)) else None

            case col: DoubleColumn =>
              // since -inf is not a legal value, it's a great starting point for
              // finding the max because any legal value will be greater.
              var zmax = Double.NegativeInfinity
              val seen = RangeUtil.loopDefined(range, col) { i =>
                val z = col(i)
                if (z > zmax) zmax = z
              }
              if (zmax > Double.NegativeInfinity) Some(BigDecimal(zmax)) else None

            case col: NumColumn =>
              // we can just use a null BigDecimal to signal that we haven't
              // found a value yet.
              var zmax: BigDecimal = null
              RangeUtil.loopDefined(range, col) { i =>
                val z = col(i)
                if (zmax == null || z > zmax) zmax = z
              }
              if (zmax != null) Some(zmax) else None

            case _ => None
          }

          // now we just find the max out of all of our column types
          if (maxs.isEmpty) None else maxs.suml(monoid)
        }
      }

      def extract(res: Result): Table =
        extractValue(res) map { v => Table.constDecimal(Set(v)) } getOrElse Table.empty

      def extractValue(res: Result) = res map { CNum(_) }
    }

    object Min extends Reduction(ReductionNamespace, "min") {
      type Result = Option[BigDecimal]

      implicit val monoid = new Monoid[Result] {
        def zero = None
        def append(left: Result, right: => Result): Result = {
          (for (l <- left; r <- right) yield l min r) orElse left orElse right
        }
      }

      val tpe = UnaryOperationType(JNumberT, JNumberT)
      
      def reducer(ctx: EvaluationContext): Reducer[Result] = new CReducer[Result] {
        def reduce(schema: CSchema, range: Range): Result = {
          val mins = schema.columns(JNumberT) map {
            case col: LongColumn =>
              // for longs, we'll use a Boolean to track whether zmin was really
              // seen or not.
              var zmin = Long.MaxValue
              val seen = RangeUtil.loopDefined(range, col) { i =>
                val z = col(i)
                if (z < zmin) zmin = z
              }
              if (seen) Some(BigDecimal(zmin)) else None

            case col: DoubleColumn =>
              // since +inf is not a legal value, it's a great starting point for
              // finding the min because any legal value will be less.
              var zmin = Double.PositiveInfinity
              RangeUtil.loopDefined(range, col) { i =>
                val z = col(i)
                if (z < zmin) zmin = z
              }
              if (zmin < Double.PositiveInfinity) Some(BigDecimal(zmin)) else None

            case col: NumColumn =>
              // we can just use a null BigDecimal to signal that we haven't
              // found a value yet.
              var zmin: BigDecimal = null
              RangeUtil.loopDefined(range, col) { i =>
                val z = col(i)
                if (zmin == null || z < zmin) zmin = z
              }
              if (zmin != null) Some(zmin) else None

            case _ => None
          }

          // now we just find the min out of all of our column types
          if (mins.isEmpty) None else mins.suml(monoid)
        }
      }

      def extract(res: Result): Table =
        extractValue(res) map { v => Table.constDecimal(Set(v)) } getOrElse Table.empty

      def extractValue(res: Result) = res map { CNum(_) }
    }

    val SumMonoid = implicitly[Monoid[Sum.Result]]
    object Sum extends Reduction(ReductionNamespace, "sum") {
      type Result = Option[BigDecimal]

      implicit val monoid = SumMonoid

      val tpe = UnaryOperationType(JNumberT, JNumberT)

      def reducer(ctx: EvaluationContext): Reducer[Result] = new CReducer[Result] {
        def reduce(schema: CSchema, range: Range) = {

          val sum = schema.columns(JNumberT) map {

            case col: LongColumn =>
              val ls = new LongAdder()
              val seen = RangeUtil.loopDefined(range, col) { i => ls.add(col(i)) }
              if (seen) Some(ls.total) else None

            // TODO: exactness + overflow
            case col: DoubleColumn =>
              var t = 0.0
              var seen = RangeUtil.loopDefined(range, col) { i => t += col(i) }
              if (seen) Some(BigDecimal(t)) else None

            case col: NumColumn =>
              var t = BigDecimal(0)
              val seen = RangeUtil.loopDefined(range, col) { i => t += col(i) }
              if (seen) Some(t) else None

            case _ => None
          }

          if (sum.isEmpty) None else sum.suml(monoid)
        }
      }

      def extract(res: Result): Table = {
        extractValue(res) map { v => Table.constDecimal(Set(v)) } getOrElse Table.empty
      }

      def extractValue(res: Result) = res map { CNum(_) }
    }

    val MeanMonoid = implicitly[Monoid[Mean.Result]]
    object Mean extends Reduction(ReductionNamespace, "mean") {
      type Result = Option[InitialResult]
      type InitialResult = (BigDecimal, Long) // (sum, count)
      
      implicit val monoid = MeanMonoid
      
      val tpe = UnaryOperationType(JNumberT, JNumberT)

      def reducer(ctx: EvaluationContext): Reducer[Result] = new Reducer[Result] {
        def reduce(schema: CSchema, range: Range): Result = {
          val results = schema.columns(JNumberT) map {

            case col: LongColumn =>
              val ls = new LongAdder()
              var count = 0L
              RangeUtil.loopDefined(range, col) { i =>
                  ls.add(col(i))
                  count += 1L
              }
              if (count > 0L) Some((ls.total, count)) else None

            case col: DoubleColumn =>
              var count = 0L
              var t = BigDecimal(0)
              RangeUtil.loopDefined(range, col) { i =>
                  t += col(i)
                  count += 1L
              }
              if (count > 0L) Some((t, count)) else None

            case col: NumColumn =>
              var count = 0L
              var t = BigDecimal(0)
              RangeUtil.loopDefined(range, col) { i =>
                  t += col(i)
                  count += 1L
              }
              if (count > 0L) Some((t, count)) else None

            case _ => None
          }

          if (results.isEmpty) None else results.suml(monoid)
        }
      }

      def extract(res: Result): Table = extractValue(res) map {
        case v => Table.constDecimal(Set(v))
      } getOrElse Table.empty

      def extractValue(res: Result): Option[CNum] = res map {
        case (sum, count) => CNum(sum / count)
      }
    }
    
    object GeometricMean extends Reduction(ReductionNamespace, "geometricMean") {
      type Result = Option[InitialResult]
      type InitialResult = (BigDecimal, Long)
      
      implicit val monoid = new Monoid[Result] {
        def zero = None
        def append(left: Result, right: => Result) = {
          val both = for ((l1, l2) <- left; (r1, r2) <- right) yield (l1 * r1, l2 + r2)
          both orElse left orElse right
        }
      }

      val tpe = UnaryOperationType(JNumberT, JNumberT)

      def reducer(ctx: EvaluationContext): Reducer[Result] = new Reducer[Option[(BigDecimal, Long)]] {
        def reduce(schema: CSchema, range: Range): Result = {
          val results = schema.columns(JNumberT) map {
            case col: LongColumn =>
              var prod = BigDecimal(1)
              var count = 0L
              RangeUtil.loopDefined(range, col) { i =>
                  prod *= col(i)
                  count += 1L
              }
              if (count > 0) Some((prod, count)) else None

            case col: DoubleColumn =>
              var prod = BigDecimal(1)
              var count = 0L
              RangeUtil.loopDefined(range, col) { i =>
                  prod *= col(i)
                  count += 1L
              }
              if (count > 0) Some((prod, count)) else None

            case col: NumColumn =>
              var prod = BigDecimal(1)
              var count = 0L
              RangeUtil.loopDefined(range, col) { i =>
                  prod *= col(i)
                  count += 1L
              }
              if (count > 0) Some((prod, count)) else None

            case _ => None
          }

          if (results.isEmpty) None else results.suml(monoid)
        }
      }

      def extract(res: Result): Table = extractValue(res) map {
        v => Table.constDecimal(Set(v))
      } getOrElse {
        Table.empty
      }

      def extractValue(res: Result) = res map {
        case (prod, count) => math.pow(prod.toDouble, 1 / count.toDouble)
      } filter(StdLib.doubleIsDefined) map {
        mean => CNum(mean)
      }
    }
    
    val SumSqMonoid = implicitly[Monoid[SumSq.Result]]
    object SumSq extends Reduction(ReductionNamespace, "sumSq") {
      type Result = Option[BigDecimal]

      implicit val monoid = SumSqMonoid

      val tpe = UnaryOperationType(JNumberT, JNumberT)

      def reducer(ctx: EvaluationContext): Reducer[Result] = new Reducer[Result] {
        def reduce(schema: CSchema, range: Range): Result = {
          val result = schema.columns(JNumberT) map {

            case col: LongColumn =>
              val ls = new LongAdder()
              val seen = RangeUtil.loopDefined(range, col) { i =>
                ls.addSquare(col(i))
              }
              if (seen) Some(ls.total) else None

            case col: DoubleColumn =>
              var t = BigDecimal(0)
              val seen = RangeUtil.loopDefined(range, col) { i =>
                t += BigDecimal(col(i)) pow 2
              }
              if (seen) Some(t) else None

            case col: NumColumn =>
              var t = BigDecimal(0)
              val seen = RangeUtil.loopDefined(range, col) { i =>
                t += col(i) pow 2
              }
              if (seen) Some(t) else None

            case _ => None
          }
            
          if (result.isEmpty) None else result.suml(monoid)
        }
      }

      def extract(res: Result): Table =
        extractValue(res) map { v => Table.constDecimal(Set(v)) } getOrElse Table.empty

      def extractValue(res: Result) = res map { CNum(_) }
    }

    class CountSumSumSqReducer extends Reducer[Option[(Long, BigDecimal, BigDecimal)]] {
      def reduce(schema: CSchema, range: Range):
        Option[(Long, BigDecimal, BigDecimal)] = {
        val result = schema.columns(JNumberT) map {
          case col: LongColumn =>
            var count = 0L
            var sum = new LongAdder()
            var sumsq = new LongAdder()
            val seen = RangeUtil.loopDefined(range, col) { i =>
                val z = col(i)
                count += 1
                sum.add(z)
                sumsq.addSquare(z)
            }

            if (seen) Some((count, sum.total, sumsq.total)) else None

          case col: DoubleColumn =>
            var count = 0L
            var sum = BigDecimal(0)
            var sumsq = BigDecimal(0)
            val seen = RangeUtil.loopDefined(range, col) { i =>
                val z = BigDecimal(col(i))
                count += 1
                sum += z
                sumsq += z pow 2
            }

            if (seen) Some((count, sum, sumsq)) else None

          case col: NumColumn =>
            var count = 0L
            var sum = BigDecimal(0)
            var sumsq = BigDecimal(0)
            val seen = RangeUtil.loopDefined(range, col) { i =>
                val z = col(i)
                count += 1
                sum += z
                sumsq += z pow 2
            }

            if (seen) Some((count, sum, sumsq)) else None

          case _ => None
        }

        if (result.isEmpty) None else result.suml
      }
    }

    val VarianceMonoid = implicitly[Monoid[Variance.Result]]
    object Variance extends Reduction(ReductionNamespace, "variance") {
      type Result = Option[InitialResult]

      type InitialResult = (Long, BigDecimal, BigDecimal) 

      implicit val monoid = VarianceMonoid

      val tpe = UnaryOperationType(JNumberT, JNumberT)
      
      def reducer(ctx: EvaluationContext): Reducer[Result] = new CountSumSumSqReducer()

      def extract(res: Result): Table = extractValue(res) map { v =>
          Table.constDecimal(Set(v))
      } getOrElse Table.empty

      // todo using toDouble is BAD
      def extractValue(res: Result): Option[CNum] = res map {
        case (count, sum, sumsq) if count > 0 =>
          val n = (sumsq - (sum * sum / count)) / count
          CNum(n)
      }
    }
    
    val StdDevMonoid = implicitly[Monoid[StdDev.Result]]
    object StdDev extends Reduction(ReductionNamespace, "stdDev") {
      type Result = Option[InitialResult]
      type InitialResult = (Long, BigDecimal, BigDecimal) // (count, sum, sumsq)
      
      implicit val monoid = StdDevMonoid

      val tpe = UnaryOperationType(JNumberT, JNumberT)

      def reducer(ctx: EvaluationContext): Reducer[Result] = new CountSumSumSqReducer()

      def extract(res: Result): Table = extractValue(res) map { v =>
        Table.constDecimal(Set(v))
      } getOrElse Table.empty

      // todo using toDouble is BAD
      def extractValue(res: Result): Option[CNum] = res map {
        case (count, sum, sumsq) if count > 0 =>
          val n = sqrt(count * sumsq - sum * sum) / count
          CNum(n)
      }
    }
    
    object Forall extends Reduction(ReductionNamespace, "forall") {
      type Result = Option[Boolean]
      
      val tpe = UnaryOperationType(JBooleanT, JBooleanT)
      
      implicit val monoid = new Monoid[Option[Boolean]] {
        def zero = None
        
        def append(left: Option[Boolean], right: => Option[Boolean]) = {
          val both = for (l <- left; r <- right) yield l && r
          both orElse left orElse right
        }
      }
      
      def reducer(ctx: EvaluationContext): Reducer[Result] = new CReducer[Result] {
        def reduce(schema: CSchema, range: Range) = {
          if (range.isEmpty) {
            None
          } else {
            var back = true
            var defined = false
            
            schema.columns(JBooleanT) foreach { c =>
              val bc = c.asInstanceOf[BoolColumn]
              var acc = back
              
              val idef = RangeUtil.loopDefined(range, bc) { i =>
                acc &&= bc(i)
              }
              
              back &&= acc
              
              if (idef) {
                defined = true
              }
            }
            
            if (defined)
              Some(back)
            else
              None
          }
        }
      }

      private val default = CBoolean(true)
        
      def extract(res: Result): Table = extractValue(res) map { v =>
        Table.constBoolean(Set(v))
      } getOrElse Table.constBoolean(Set(default))

      def extractValue(res: Result): Option[CBoolean] = res map { b =>
        CBoolean(b)
      } orElse Some(default)
    }
    
    object Exists extends Reduction(ReductionNamespace, "exists") {
      type Result = Option[Boolean]
      
      val tpe = UnaryOperationType(JBooleanT, JBooleanT)
      
      implicit val monoid = new Monoid[Option[Boolean]] {
        def zero = None
        
        def append(left: Option[Boolean], right: => Option[Boolean]) = {
          val both = for (l <- left; r <- right) yield l || r
          both orElse left orElse right
        }
      }
      
      def reducer(ctx: EvaluationContext): Reducer[Result] = new CReducer[Result] {
        def reduce(schema: CSchema, range: Range) = {
          if (range.isEmpty) {
            None
          } else {
            var back = false
            var defined = false
            
            schema.columns(JBooleanT) foreach { c =>
              val bc = c.asInstanceOf[BoolColumn]
              var acc = back
              
              val idef = RangeUtil.loopDefined(range, bc) { i =>
                acc ||= bc(i)
              }
              
              back ||= acc
              
              if (idef) {
                defined = true
              }
            }
            
            if (defined)
              Some(back)
            else
              None
          }
        }
      }
        
      private val default = CBoolean(false)

      def extract(res: Result): Table = extractValue(res) map { v =>
        Table.constBoolean(Set(v))
      } getOrElse Table.constBoolean(Set(default))

      def extractValue(res: Result): Option[CBoolean] = res map { b =>
        CBoolean(b)
      } orElse Some(default)
    }
  }
}
