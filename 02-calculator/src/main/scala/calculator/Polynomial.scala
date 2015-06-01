package calculator

object Polynomial {

  def computeDelta(a: Signal[Double], b: Signal[Double],
      c: Signal[Double]): Signal[Double] = {
    Signal {
      Math.pow(b(), 2) - 4 * a() * c()
    }
  }

  def computeSolutions(a: Signal[Double], b: Signal[Double],
      c: Signal[Double], delta: Signal[Double]): Signal[Set[Double]] = {

    Signal {
      def calc(rooted: Double) = (-b() + rooted) / (2 * a())

      if (delta() > 0) Set(calc(Math.sqrt(delta())), calc(-Math.sqrt(delta())))
      else if (delta() == 0) Set(calc(0))
      else Set()
    }
  }
}
