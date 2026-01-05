package utils

import besom.*
import besom.internal.Context
import besom.api.aws.Provider as AwsProvider
import besom.api.kubernetes.Provider as K8sProvider

trait Resource[In, Out, LocalIn, LocalOut] {

  def make(inputParams: In)(using c: Context): Output[Out]

  def makeLocal(inputParams: LocalIn)(using c: Context): Output[LocalOut]
}

