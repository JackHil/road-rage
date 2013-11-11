// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.map.make

import scala.collection.mutable
import utexas.aorta.map.Coordinate
import utexas.aorta.common.Util

class BuildingScraper() {
  // For now, just store one point associated with the building
  case class Bldg(road: Option[String], point: Coordinate, residential: Boolean)
  private val bldgs = new mutable.ListBuffer[Bldg]()

  // TODO missing any?
  private val bldg_tags = Set("addr:housenumber", "shop")
  private val nonresidence_tags = Set("amenity", "shop")
  private def is_bldg(tags: Map[String, String]) = tags.keys.exists(bldg_tags.contains(_))
  private def is_residential(tags: Map[String, String]) =
    !tags.keys.exists(nonresidence_tags.contains(_))

  def scrape(osm: OsmReader) {
    osm.listen("building-scraper", _ match {
      // Grab an arbitrary point from the building
      case EV_OSM(elem) if is_bldg(elem.tags) =>
        bldgs += Bldg(elem.tags.get("addr:street"), elem.points.head, is_residential(elem.tags))
      case _ =>
    })
  }

  def group(graph: PreGraph3) {
    Util.log(s"Matching ${bldgs.size} buildings to roads...")
    // First group roads by their name for fast pruning
    val roads_by_name = graph.roads.groupBy(_.name)
    for (bldg <- bldgs) {
      // for now, ignore roads without a name, or with a name we don't know
      if (bldg.road.isDefined && roads_by_name.contains(bldg.road.get)) {
        val candidates = roads_by_name(bldg.road.get).flatMap(_.directed_roads)
        val dr = candidates.minBy(dr => dr.from.location.dist_to(bldg.point))
        if (bldg.residential) {
          dr.residential_count += 1
        } else {
          dr.shop_count += 1
        }
      }
    }
  }
}
