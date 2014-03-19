// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.dm

import utexas.aorta.sim.Simulation
import utexas.aorta.ui.{GUI, MapCanvas}
import utexas.aorta.common.Util

object Explorer {
  def main(args: Array[String]) {
    args.head match {
      case "gui" => {
        val canvas = new MapCanvas(Util.process_args(args.tail))
        setup(canvas)
        GUI.run(canvas)
      }
      case "bayes" => {
        classify_experiment(Util.process_args(args.tail))
      }
    }
  }

  private def setup(canvas: MapCanvas) {
    val graph = canvas.sim.graph
    val osm = OsmGraph.convert(graph)
    val percentile = 1.0

    Util.log("Creating heatmap for way length...")
    canvas.show_heatmap(osm.convert_costs(osm.lengths), percentile, "way length")
    Util.log("Creating heatmap for number of connections...")
    canvas.show_heatmap(
      osm.convert_costs(osm.succs.mapValues(_.size.toDouble)), percentile,
      "number of connections"
    )
    Util.log("Creating heatmap for popularity...")
    canvas.show_heatmap(
      osm.convert_costs(osm.popular_ways), percentile, "popularity of way in shortest path"
    )
    Util.log("Creating heatmap for PageRank...")
    canvas.show_heatmap(osm.convert_costs(osm.pagerank), percentile, "pagerank")
  }

  private def classify_experiment(sim: Simulation) {
    val bins = 50
    val osm = OsmGraph.convert(sim.graph)
    val raw = osm.scrape_data()
    val fixer = Preprocessing.summarize(raw, bins)
    val instances = raw.map(r => fixer.transform(r))
    val bayes = new NaiveBayesClassifier(fixer.labels, bins)
    bayes.train(instances, Nil)
    bayes.summarize(instances)
  }
}
