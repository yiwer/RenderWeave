package cn.hbads.renderweave.inference.eval.visual;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredMetricGoldenTest {
    @Test
    void characterAndWordEditGoldensIncludeEmptyReferenceSemantics() {
        var character = LayeredMetricMath.characters("kitten", "sitting");
        var words = LayeredMetricMath.words("alpha beta gamma", "alpha delta");
        var insertionOnly = LayeredMetricMath.characters("", "abc");
        var empty = LayeredMetricMath.characters("", "");

        assertEquals(new LayeredMetricMath.EditCounts(6, 7, 2, 1, 0), character);
        assertEquals(5_000, character.errorRateBps());
        assertEquals(new LayeredMetricMath.EditCounts(3, 2, 1, 0, 1), words);
        assertEquals(6_666, words.errorRateBps());
        assertEquals(new LayeredMetricMath.EditCounts(0, 3, 0, 3, 0), insertionOnly);
        assertEquals(10_000, insertionOnly.errorRateBps());
        assertEquals(0, empty.errorRateBps());
    }

    @Test
    void geometryAndLockedApGoldensCoverPerfectPartialAndEmptySets() {
        var gold = List.of(
                new LayeredMetricMath.Detection("a", "SLOT", box(0, 0, 100, 100), 10_000),
                new LayeredMetricMath.Detection("b", "SLOT", box(200, 0, 300, 100), 10_000));
        var predicted = List.of(
                new LayeredMetricMath.Detection("pa", "SLOT", box(0, 0, 100, 100), 9_000),
                new LayeredMetricMath.Detection("noise", "SLOT", box(500, 500, 600, 600), 8_000));

        assertEquals(10_000, LayeredMetricMath.iouBps(box(0, 0, 100, 100), box(0, 0, 100, 100)));
        assertEquals(3_333, LayeredMetricMath.iouBps(box(0, 0, 100, 100), box(50, 0, 150, 100)));
        var score = LayeredMetricMath.detection(gold, predicted);
        assertEquals(2, score.expected());
        assertEquals(2, score.predicted());
        assertEquals(1, score.matchedAtIou50());
        assertEquals(5_049, score.ap5095Bps());
        assertEquals(10_000, LayeredMetricMath.detection(List.of(), List.of()).ap5095Bps());
        assertEquals(0, LayeredMetricMath.detection(gold, List.of()).ap5095Bps());
    }

    @Test
    void graphRepeatBindingAndTopologyGoldensAreHandCheckable() {
        var edges = LayeredMetricMath.setCounts(
                List.of("a>b", "b>c", "c>d"), List.of("a>b", "b>c", "x>y"));
        assertEquals(new LayeredMetricMath.SetCounts(3, 3, 2), edges);
        assertEquals(6_666, edges.precisionBps());
        assertEquals(6_666, edges.recallBps());
        assertEquals(6_666, edges.f1Bps());
        assertFalse(LayeredMetricMath.hasCycle(List.of("a>b", "b>c")));
        assertTrue(LayeredMetricMath.hasCycle(List.of("a>b", "b>c", "c>a")));

        var repeat = LayeredMetricMath.repeat(
                List.of(new LayeredMetricMath.RepeatMembership("g", "i1", "slot-a"),
                        new LayeredMetricMath.RepeatMembership("g", "i2", "slot-b")),
                List.of(new LayeredMetricMath.RepeatMembership("g", "i1", "slot-a")), 2, 1);
        assertEquals(1, repeat.itemCountAbsoluteError());
        assertEquals(5_000, repeat.membership().recallBps());
        assertEquals(10_000, repeat.membership().precisionBps());

        var bindings = LayeredMetricMath.setCounts(List.of("slot-a>entity-a", "slot-b>entity-b"),
                List.of("slot-a>entity-a", "slot-b>entity-a"));
        assertEquals(5_000, bindings.f1Bps());
        assertEquals(0, LayeredMetricMath.treeEditDistance(
                List.of("root>items>item", "item>label>text"),
                List.of("root>items>item", "item>label>text")));
        assertEquals(2, LayeredMetricMath.treeEditDistance(
                List.of("root>items>item", "item>label>text"),
                List.of("root>items>item", "item>price>decimal")));
    }

    @Test
    void calibrationGoldensUseFixedBasisPointArithmetic() {
        var bins = List.of(
                new LayeredMetricMath.CalibrationBin(0, 0, 0, 0),
                new LayeredMetricMath.CalibrationBin(2, 1, 14_000, 1_000),
                new LayeredMetricMath.CalibrationBin(2, 2, 18_000, 200));

        assertEquals(1_500, LayeredMetricMath.expectedCalibrationErrorBps(bins));
        assertEquals(300, LayeredMetricMath.brierScoreBps(bins));
        assertEquals(0, LayeredMetricMath.expectedCalibrationErrorBps(List.of()));
        assertEquals(0, LayeredMetricMath.brierScoreBps(List.of()));
    }

    private static LayeredMetricMath.Box box(int left, int top, int right, int bottom) {
        return new LayeredMetricMath.Box(left, top, right, bottom);
    }
}
