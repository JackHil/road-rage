// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.map

import scala.collection.mutable

import utexas.aorta.common.algorithms.AStar
import utexas.aorta.common.Util

class ZoneMap(graph: Graph) {
  private val mapping = ZoneMap.partition(graph)
  val zones = mapping.values.toSet
  Util.log(s"${graph.directed_roads.size} DRs partitioned into ${zones.size} zones")
  val links = zones.map(zone => zone -> (zone.ports.map(dr => mapping(dr)).toSet - zone)).toMap

  def apply(dr: DirectedRoad) = mapping(dr)
}

case class Zone(roads: Set[DirectedRoad]) {
  val center = compute_center
  // Member roads that have successors outside the set
  // TODO since partition isnt disjoint, misses some connections
  val ports = roads.filter(dr => dr.succs.exists(succ => !roads.contains(succ)))

  private def compute_center(): Coordinate = {
    val pts = roads.map(dr => dr.edges.head.approx_midpt)
    val avg_x = pts.map(_.x).sum / roads.size
    val avg_y = pts.map(_.y).sum / roads.size
    return new Coordinate(avg_x, avg_y)
  }
}

object ZoneMap {
  private val max_size = 50

  def partition(graph: Graph): Map[DirectedRoad, Zone] = {
    Util.log("Partitioning the map into zones...")
    val mapping = new mutable.HashMap[DirectedRoad, Zone]()
    val open = new mutable.TreeSet[DirectedRoad]()  // TODO queue?
    open ++= graph.directed_roads
    while (open.nonEmpty) {
      Util.log(s"  ${open.size} roads left to process")
      val base = open.head
      // TODO need an easier way to call this beast!
      val path = AStar.path(
        base, base, (step: DirectedRoad) => step.succs,
        (_: DirectedRoad, next: DirectedRoad, _: (Double, Double)) => (next.freeflow_time, 0),
        (state: DirectedRoad, goal: DirectedRoad) => (state.end_pt.dist_to(goal.end_pt), 0),
        (a: (Double, Double), b: (Double, Double)) => (a._1 + b._1, a._2 + b._2),
        allow_cycles = true
      )
      val new_zone = new mutable.HashSet[DirectedRoad]()
      new_zone ++= path
      open --= new_zone

      // Merge zones by finding common overlap
      // TODO dont cross different road types?
      val common_roads = new_zone.intersect(mapping.keys.toSet)
      while (new_zone.size < max_size && common_roads.nonEmpty) {
        // TODO should keep common_roads up-to-date as we merge
        val candidate = common_roads.head
        common_roads -= candidate
        if ((new_zone ++ mapping(candidate).roads).size < max_size) {
          new_zone ++= mapping(candidate).roads
        }
      }
      val zone = Zone(new_zone.toSet)
      // TODO overwrites. make the partitioning disjt.
      zone.roads.foreach(dr => mapping(dr) = zone)
    }
    return mapping.toMap
  }
}
