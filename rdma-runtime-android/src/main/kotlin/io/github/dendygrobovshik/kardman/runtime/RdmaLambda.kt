package io.github.dendygrobovshik.kardman.runtime

class RdmaFunction0<R>(private val id: Long) : Function0<R> {
    override fun invoke(): R = RdmaComposeHost.nativeInvokeLambda(id, emptyArray()) as R
}

class RdmaFunction1<P1, R>(private val id: Long) : Function1<P1, R> {
    override fun invoke(p1: P1): R = RdmaComposeHost.nativeInvokeLambda(id, arrayOf(p1 as Any?)) as R
}

class RdmaFunction2<P1, P2, R>(private val id: Long) : Function2<P1, P2, R> {
    override fun invoke(p1: P1, p2: P2): R = RdmaComposeHost.nativeInvokeLambda(id, arrayOf(p1 as Any?, p2 as Any?)) as R
}

class RdmaFunction3<P1, P2, P3, R>(private val id: Long) : Function3<P1, P2, P3, R> {
    override fun invoke(p1: P1, p2: P2, p3: P3): R = RdmaComposeHost.nativeInvokeLambda(id, arrayOf(p1 as Any?, p2 as Any?, p3 as Any?)) as R
}
