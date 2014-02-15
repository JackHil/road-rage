// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.experiments

import org.jfree.data.xy.{XYSeries, XYSeriesCollection}
import org.jfree.chart.{JFreeChart, ChartFactory, ChartFrame}
import org.jfree.chart.plot.PlotOrientation
import scala.io.Source
import java.util.zip.GZIPInputStream
import java.io.{BufferedInputStream, FileInputStream}

import utexas.aorta.common.Util

object PlotResults extends PlotUtil {
  def main(args: Array[String]) {
    args.head match {
      case "time_vs_priority" => show(time_vs_priority(read_times(args.tail.head)))
    }
  }

  private def time_vs_priority(s: ScenarioTimes): JFreeChart = {
    val data = new XYSeriesCollection()
    for ((mode, idx) <- s.modes.zipWithIndex) {
      add(data, mode, s.agents.map(a => (a.priority, a.times(idx) / a.ideal_time)))
    }
    return scatter_plot("Time vs priority", "Priority", "Trip time / ideal time", data)
  }
}

// TODO histograms and box plots too. jfreechart seems better at those?
trait PlotUtil {
  private def read(fn: String) =
    if (fn.endsWith(".gz"))
      Source.fromInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(fn))))
    else
      Source.fromFile(fn)

  protected def read_times(fn: String): ScenarioTimes = {
    val lines = read(fn).getLines
    val header = lines.next.split(" ")
    Util.assert_eq(header.take(3).toList, List("agent", "priority", "ideal_time"))
    return ScenarioTimes(
      ScenarioTag(fn), header.drop(3),
      lines.map(l => TripTimeResult(l.split(" ").map(_.toDouble))).toArray
    )
  }

  protected def add(chart: XYSeriesCollection, name: String, data: Array[(Double, Double)]) {
    val series = new XYSeries(name)
    for ((x, y) <- data) {
      series.add(x, y)
    }
    chart.addSeries(series)
  }

  protected def scatter_plot(title: String, x: String, y: String, data: XYSeriesCollection) =
    ChartFactory.createScatterPlot(
      title, x, y, data, PlotOrientation.VERTICAL, true, false, false
    )

  protected def show(chart: JFreeChart) {
    val frame = new ChartFrame("A nefarious plot", chart)
    frame.pack()
    frame.setVisible(true)
  }
}

case class ScenarioTag(id: String, map: String)
case class TripTimeResult(priority: Double, ideal_time: Double, times: Array[Double])
case class ScenarioTimes(tag: ScenarioTag, modes: Array[String], agents: Array[TripTimeResult])

// TODO stuff here is the dual of stuff in Metrics. pair them together somehow?
object ScenarioTag {
  // fn is of the form $metric.$scenario.$map, possibly with a trailing .gz
  def apply(fn: String): ScenarioTag = {
    val pieces = fn.split("\\.")
    return new ScenarioTag(pieces(1), pieces(2))
  }
}
object TripTimeResult {
  def apply(fields: Array[Double]) = new TripTimeResult(fields(1), fields(2), fields.drop(3))
}
