package cn.chuanwise.kotlinsuspendprojection.idea;

enum ProjectionKind {
    BLOCKING("Blocking", null),
    COMPLETION_STAGE("CompletionStage", "java.util.concurrent.CompletionStage"),
    COMPLETABLE_FUTURE("CompletableFuture", "java.util.concurrent.CompletableFuture"),
    FUTURE("Future", "java.util.concurrent.Future");

    private final String suffix;
    private final String wrapperClass;

    ProjectionKind(String suffix, String wrapperClass) {
        this.suffix = suffix;
        this.wrapperClass = wrapperClass;
    }

    String projectedName(String sourceName) {
        return this == BLOCKING ? sourceName : sourceName + suffix;
    }

    String wrapperClass() {
        return wrapperClass;
    }
}
