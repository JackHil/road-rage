package utexas.map

import utexas.map.make.Reader

import utexas.Util

class Graph(val roads: List[Road], val edges: List[Edge],
            val vertices: List[Vertex], val width: Double, val height: Double)
{
  // also fixed constraints: residential types and decent length
  // Note this one of those scary things that might not return
  def random_edge_except(except: Set[Edge]): Edge = {
    val min_len = 1.0 // TODO cfg. what unit is this in?
    val e = Util.choose_rand(edges)
    if (!except.contains(e) && e.road.road_type == "residential" && e.length > min_len) {
      return e
    } else {
      return random_edge_except(except)
    }
  }



  
  Util.log("Dividing the map into wards...")
  Util.log_push
  // Mike has "super-edges" in between wards, and Dustin has the "highway". All
  // roads belong to this special ward.
  //val (wards, special_ward) = Ward.construct_mikes_wards(this)
  val (wards, special_ward) = Ward.construct_dustins_wards(this)

  // TODO eventually just associate Ward with the Road directly
  val road2ward: Map[Road, Ward] = (for (w <- special_ward :: wards; r <- w.roads)
                                     yield (r, w)).toMap
  Util.log("The map has " + (wards.size + 1) + " wards")
  Util.log_pop

  // wards are by Road, let's say.
  def ward(r: Road) = if (road2ward.contains(r))
                        road2ward(r)
                      else
                        special_ward
}

object Graph {
  def load(fn: String) = (new Reader(fn)).load_map
}
