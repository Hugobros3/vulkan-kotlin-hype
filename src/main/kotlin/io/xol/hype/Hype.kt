package io.xol.hype

import kotlin.math.sqrt
import kotlin.properties.Delegates.vetoable;

fun main(args: Array<String>) {
	println("hello world")
	
	var f = Fun("Marcel")
	
	println(f.intern)
	
	var z = Fax<Int>()
	z.trumpet = 50
	
	println("${z.trumpet}")
	
	var wow = arrayOf(4F, 3F)
	println(wow.length())

	println(Array(0) {0F} .length())

	var notZero by vetoable(5) {
		_, _, newValue -> newValue != 0
	}

	println(notZero)
	notZero = 4
	println(notZero)
	notZero = 0
	println(notZero)

	assert(notZero != 0)
}

fun Array<Float>.length() : Float {
	return sqrt(fold(0F) {s, v -> s + v * v})
}

open class Fun(val name: String) {
	val intern = "Bouse"
}

class Fax<T> : Fun("Personne") {
	var trumpet : T? = null
}