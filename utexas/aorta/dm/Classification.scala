// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.dm

import scala.collection.mutable

// The feature values have been discretized; the possible values are [0, bins)
case class LabeledInstance(label: String, features: List[Int])
case class UnlabeledInstance(features: List[Int])

abstract class Classifier(labels: Set[String], bins: Int) {
  def train(training_data: List[LabeledInstance], validation_data: List[LabeledInstance])

  def classify(instance: UnlabeledInstance): String

  protected def normalize[K](m: Map[K, Int]): Map[K, Double] = {
    val sum = m.values.sum
    return m.mapValues(count => count.toDouble / sum)
  }
}

class NaiveBayesClassifier(labels: Set[String], bins: Int) extends Classifier(labels, bins) {
  private var priors: Map[String, Double] = Map()
  private val features = new mutable.HashMap[(String, Int, Int), Double]()

  // TODO add different laplace smoothing parameters k, choose the best using validation_data
  override def train(training_data: List[LabeledInstance], validation_data: List[LabeledInstance]) {
    // the key is just the label
    val prior_counts = new mutable.HashMap[String, Int]().withDefaultValue(0)
    // the key is (label, feature idx, bin value)
    val feature_counts = new mutable.HashMap[(String, Int, Int), Int]().withDefaultValue(0)

    for (instance <- training_data) {
      prior_counts(instance.label) += 1
      for ((bin, feature) <- instance.features.zipWithIndex) {
        feature_counts((instance.label, feature, bin)) += 1
      }
    }
    priors = normalize(prior_counts.toMap)
    val num_features = training_data.head.features.size
    for (label <- labels) {
      for (feature <- Range(0, num_features)) {
        val denominator = Range(0, bins).map(value => feature_counts((label, feature, value))).sum
        for (value <- Range(0, bins)) {
          val key = (label, feature, value)
          features(key) = feature_counts(key) / denominator
        }
      }
    }
  }

  override def classify(instance: UnlabeledInstance): String = {
    return labels.maxBy(label => posterior(instance, label))
  }

  // Returns log(p(instance and class = label))
  private def posterior(instance: UnlabeledInstance, label: String) =
    math.log(priors(label)) + instance.features.zipWithIndex.map({
      case (bin, feature) => math.log(features((label, feature, bin)))
    }).sum
}
