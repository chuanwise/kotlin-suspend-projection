package cn.chuanwise.kotlinsuspendprojection.annotations

/** Selects a public interface or suspend function for projection generation. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class SuspendProjection
