package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.DocumentVisionPreprocessor;
import cn.hbads.renderweave.inference.vision.VisualEvidenceAcquisition;

/** Application wiring bridge; live workers receive only the successor seam and its exact policy. */
interface ConfiguredVisualEvidenceAcquisition
        extends DocumentVisionPreprocessor, VisualEvidenceAcquisition {
    AcquisitionPolicy acquisitionPolicy();
}
