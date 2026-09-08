package io.devkit.chartkit.charts

import io.devkit.chartkit.annotation.ChartAnnotation
import io.devkit.chartkit.layer.annotation.ResolvedAnnotation
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.model.resolveOrDefault

/**
 * Resolves each annotation's domain positions through the chart's own resolver.
 *
 * The same [ChartXResolver] the data went through, which is the whole reason
 * `verticalRule(at = "Mar")` and `verticalRule(at = releaseMillis)` both work
 * without a separate annotation type per axis kind — and why a custom resolver
 * taught about an application's own date type applies to its annotations too.
 *
 * Runs once per data change, in the same `remember` the layers are built in.
 */
internal fun resolveAnnotations(
    annotations: List<ChartAnnotation>,
    xResolver: ChartXResolver,
): List<ResolvedAnnotation> = annotations.map { annotation ->
    when (annotation) {
        is ChartAnnotation.VerticalRule -> ResolvedAnnotation(
            annotation = annotation,
            domainStart = xResolver.resolveOrDefault(annotation.at),
        )
        is ChartAnnotation.EventMarker -> ResolvedAnnotation(
            annotation = annotation,
            domainStart = xResolver.resolveOrDefault(annotation.at),
        )
        is ChartAnnotation.DomainRange -> ResolvedAnnotation(
            annotation = annotation,
            domainStart = xResolver.resolveOrDefault(annotation.from),
            domainEnd = xResolver.resolveOrDefault(annotation.to),
        )
        is ChartAnnotation.Region -> ResolvedAnnotation(
            annotation = annotation,
            domainStart = xResolver.resolveOrDefault(annotation.domainFrom),
            domainEnd = xResolver.resolveOrDefault(annotation.domainTo),
        )
        // Value-space annotations have no domain position to resolve.
        is ChartAnnotation.HorizontalRule, is ChartAnnotation.ValueRange ->
            ResolvedAnnotation(annotation)
    }
}
