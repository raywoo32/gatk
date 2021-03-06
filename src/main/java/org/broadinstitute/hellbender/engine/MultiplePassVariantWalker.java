package org.broadinstitute.hellbender.engine;

import htsjdk.variant.variantcontext.VariantContext;
import org.broadinstitute.hellbender.engine.filters.CountingReadFilter;
import org.broadinstitute.hellbender.engine.filters.CountingVariantFilter;
import org.broadinstitute.hellbender.engine.filters.VariantFilter;
import org.broadinstitute.hellbender.utils.SimpleInterval;

import java.util.stream.StreamSupport;

/**
 * A VariantWalker that makes multiple passes through the variants.
 * This allows the user to store internal states during early passes, which the user can then
 * process and access during later passes
 **/
public abstract class MultiplePassVariantWalker extends VariantWalker {

    protected abstract int numberOfPasses();

    /**
     * {@inheritDoc}
     *
     * Implementation of multiple-pass variant traversal.
     *
     * Overrides the default, single-pass traversal framework of {@link VariantWalkerBase} allowing for multiple
     * passes.
     *
     * NOTE: You should only override {@link #traverse()} if you are writing a new walker base class in the
     * engine package that extends this class. It is not meant to be overridden by tools outside of the
     * engine package.
     */
    @Override
    public void traverse(){
        final CountingVariantFilter countingVariantFilter = makeVariantFilter();
        final CountingReadFilter readFilter = makeReadFilter();

        for (int n = 0; n < numberOfPasses(); n++) {
            logger.info("Starting pass " + n + " through the variants");
            final int nCopyInLambda = n;
            traverseVariants(countingVariantFilter, readFilter, (vc, rc, ref, fc) -> nthPassApply(vc, rc, ref, fc, nCopyInLambda));
            logger.info("Finished pass " + n + " through the variants");

            // Process the data accumulated during the nth pass
            afterNthPass(n);
        }

        logger.info(countingVariantFilter.getSummaryLine());
        logger.info(readFilter.getSummaryLine());
    }

    /**
     *
     * nth pass through the variants. The user may store data in instance variables of the walker
     * and process them in {@link #afterNthPass} before making a second pass
     *
     * @param variant A variant record in a vcf
     * @param readsContext Reads overlapping the current variant. Will be empty if a read source (e.g. bam) isn't provided
     * @param referenceContext Reference bases spanning the current variant
     * @param featureContext A record overlapping the current variant from an auxiliary data source (e.g. gnomad)
     * @param n Which pass it is (zero-indexed)
     */
    protected abstract void nthPassApply(final VariantContext variant,
                                           final ReadsContext readsContext,
                                           final ReferenceContext referenceContext,
                                           final FeatureContext featureContext,
                                         final int n);
    /**
     * Process the data collected during the first pass. This method is called between the two traversals
     */
    protected abstract void afterNthPass(final int n);

    private void traverseVariants(final VariantFilter variantFilter, final CountingReadFilter readFilter, final VariantConsumer variantConsumer){
        StreamSupport.stream(getSpliteratorForDrivingVariants(), false)
                .filter(variantFilter)
                .forEach(variant -> {
                    final SimpleInterval variantInterval = new SimpleInterval(variant);
                    variantConsumer.consume(variant,
                            new ReadsContext(reads, variantInterval, readFilter),
                            new ReferenceContext(reference, variantInterval),
                            new FeatureContext(features, variantInterval));
                    progressMeter.update(variantInterval);
                });
    }

    @FunctionalInterface
    private interface VariantConsumer {
        void consume(final VariantContext variant, final ReadsContext readsContext, final ReferenceContext reference, final FeatureContext features);
    }

    /**
     * Make final to hide it from subclasses
     */
    @Override
    public final void apply(VariantContext variant, ReadsContext readsContext, ReferenceContext referenceContext, FeatureContext featureContext) {}
}
