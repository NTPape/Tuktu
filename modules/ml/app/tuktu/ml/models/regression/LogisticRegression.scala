package tuktu.ml.models.regression

import tuktu.ml.models.BaseModel
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Implements logistic regression
 */
class LogisticRegression(lr: Double, nIterations: Int) extends BaseModel {
    var weights: Array[Double] = Array()
    
    /**
     * Sigmoid function
     * @param x The value to apply sigmoid on
     * @return The sigmoid result
     */
    def sigmoid(x: Double) = {
        1.0 / (1.0 + Math.exp(-x))
    }
    
    /**
     * Classifies a single entry
     * @param x The entry to classify
     * @return The label probability
     */
    def classify(x: Array[Int]) = {
        var sum = 0.0
        for (i <- 0 to weights.size - 1)
            sum += weights(i) * x(i)

        // Return the sigmoid
        sigmoid(sum)
    }

    /**
     * Train on a set of labeled instances
     * @param input An array containg the data items (array of int)
     * @param labels The labels, in the same order as the input
     * @param returnMLE Whether to compute the MLE and return them (more costly operation)
     * @return A list of the maximum likelihood estimators per iteration
     */
    def train(input: Array[Array[Int]], labels: Array[Int], returnMLE: Boolean): Array[Double] = {
        (for (i <- 0 to nIterations - 1) yield {
            // Keep track of MLE
            var mle = 0.0
            
            // Go over the train data
            for ((datum, index) <- input.zipWithIndex) {
                // Get result
                val pred = classify(datum)
                
                // Compare against result and update weights
                val lbl = labels(index)
                for (j <- 0 to weights.size - 1)
                    weights(j) = weights(j) + lr * (lbl - pred) * datum(j)

                // Keep track of MLE
                if (returnMLE)
                    mle += lbl * Math.log(classify(datum)) + (1 - lbl) * Math.log(1 - classify(datum))
            }
            
            mle
        }).toArray
    }
    
    override def serialize(filename: String) {
        // Write out weights
        val oos = new ObjectOutputStream(new FileOutputStream(filename))
        oos.writeObject(weights)
        oos.close
    }
    
    override def deserialize(filename: String) {
        // Load weights
        val ois = new ObjectInputStream(new FileInputStream(filename))
        val obj = ois.readObject.asInstanceOf[Array[Double]]
        ois.close
        
        // Set back weights
        weights = obj
    }
}