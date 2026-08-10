package cn.hbads.renderweave.inference.vision;

import java.util.List;

/**
 * Narrow local-only OCR/layout port. Implementations receive normalized image bytes and must never persist them.
 */
public interface DocumentVisionPreprocessor {
    DocumentVisionCapability capability();

    DocumentVisionObservation preprocess(List<DocumentVisionArtifact> artifacts);

    static DocumentVisionPreprocessor unavailable(String diagnosticCode) {
        var capability = DocumentVisionCapability.unavailable(diagnosticCode);
        return new DocumentVisionPreprocessor() {
            @Override
            public DocumentVisionCapability capability() {
                return capability;
            }

            @Override
            public DocumentVisionObservation preprocess(List<DocumentVisionArtifact> artifacts) {
                throw new DocumentVisionException(capability.diagnosticCode());
            }

            @Override
            public String toString() {
                return "DocumentVisionPreprocessor[unavailable=" + capability.diagnosticCode() + "]";
            }
        };
    }
}
