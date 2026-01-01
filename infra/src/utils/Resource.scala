package utils

import besom.*
import besom.internal.Context

trait Resource[In, Out, LocalIn, LocalOut] {

  def make(inputParams: In)(using Context): Output[Out]

  def makeLocal(inputParams: LocalIn)(using Context): Output[LocalOut]

}
