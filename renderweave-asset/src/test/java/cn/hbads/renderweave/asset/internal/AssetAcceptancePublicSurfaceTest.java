package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetAcceptancePublicSurfaceTest {

    @Test
    void exposesOnlyTheOwnedContractsAsTopLevelPublicTypes() {
        var production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("cn.hbads.renderweave.asset");
        var publicTopLevel = production.stream()
                .filter(javaClass -> javaClass.getEnclosingClass().isEmpty())
                .filter(javaClass -> javaClass.getModifiers().contains(JavaModifier.PUBLIC))
                .map(javaClass -> javaClass.getName())
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of(
                AssetAcceptanceAuthority.class.getName(),
                "cn.hbads.renderweave.asset.api.AssetApplication",
                "cn.hbads.renderweave.asset.api.AssetResolver",
                "cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority",
                "cn.hbads.renderweave.asset.spi.AssetPersistence",
                "cn.hbads.renderweave.asset.spi.AssetBlobPersistence",
                "cn.hbads.renderweave.asset.spi.AssetFetchEndpoint",
                "cn.hbads.renderweave.asset.spi.AssetReferencePort",
                "cn.hbads.renderweave.asset.spi.AssetAuditEventSource",
                "cn.hbads.renderweave.asset.internal.AssetModule"
        ), publicTopLevel);
    }

    @Test
    void freezesTheSingleBehavioralMethodAndClosedOutcomeTypes() {
        var methods = AssetAcceptanceAuthority.class.getDeclaredMethods();
        assertEquals(1, methods.length);
        var admit = methods[0];
        assertEquals("admit", admit.getName());
        assertEquals(AssetAcceptanceAuthority.Acceptance.class, admit.getReturnType());
        assertArrayEquals(
                new Class<?>[]{byte[].class, AssetAcceptanceAuthority.AssetKind.class},
                admit.getParameterTypes()
        );
        assertTrue(Modifier.isPublic(admit.getModifiers()));
        assertTrue(Modifier.isAbstract(admit.getModifiers()));

        assertTrue(AssetAcceptanceAuthority.Acceptance.class.isSealed());
        assertEquals(
                Set.of(
                        AssetAcceptanceAuthority.Admitted.class,
                        AssetAcceptanceAuthority.Rejected.class
                ),
                Set.of(AssetAcceptanceAuthority.Acceptance.class.getPermittedSubclasses())
        );
        assertEquals(
                Set.of(
                        "Acceptance",
                        "Admitted",
                        "Rejected",
                        "AssetKind",
                        "FailureCode",
                        "FailureStage",
                        "Limit",
                        "TechnicalDescriptor",
                        "ImageDescriptor",
                        "FontDescriptor",
                        "Orientation",
                        "ColorEncoding",
                        "FontFlavor"
                ),
                Arrays.stream(AssetAcceptanceAuthority.class.getDeclaredClasses())
                        .map(Class::getSimpleName)
                        .collect(Collectors.toUnmodifiableSet())
        );
        assertTrue(AssetAcceptanceAuthority.TechnicalDescriptor.class.isSealed());
        assertEquals(
                Set.of(
                        AssetAcceptanceAuthority.ImageDescriptor.class,
                        AssetAcceptanceAuthority.FontDescriptor.class
                ),
                Set.of(AssetAcceptanceAuthority.TechnicalDescriptor.class.getPermittedSubclasses())
        );
    }

    @Test
    void freezesFailureCodesStagesLimitsAndRejectedRecordShape() {
        assertArrayEquals(
                new AssetAcceptanceAuthority.FailureCode[]{
                        AssetAcceptanceAuthority.FailureCode.ASSET_CONTENT_INVALID,
                        AssetAcceptanceAuthority.FailureCode.ASSET_CONTENT_UNSUPPORTED,
                        AssetAcceptanceAuthority.FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED
                },
                AssetAcceptanceAuthority.FailureCode.values()
        );
        assertArrayEquals(
                new AssetAcceptanceAuthority.FailureStage[]{
                        AssetAcceptanceAuthority.FailureStage.ASSET_STRUCTURE,
                        AssetAcceptanceAuthority.FailureStage.ASSET_DECODE,
                        AssetAcceptanceAuthority.FailureStage.ASSET_DESCRIPTOR
                },
                AssetAcceptanceAuthority.FailureStage.values()
        );
        assertArrayEquals(
                new String[]{
                        "assetAcceptance.rawBytes",
                        "assetAcceptance.imageEdgePixels",
                        "assetAcceptance.imageTotalPixels"
                },
                Arrays.stream(AssetAcceptanceAuthority.Limit.values())
                        .map(AssetAcceptanceAuthority.Limit::id)
                        .toArray(String[]::new)
        );

        assertTrue(AssetAcceptanceAuthority.Rejected.class.isRecord());
        var components = AssetAcceptanceAuthority.Rejected.class.getRecordComponents();
        assertArrayEquals(
                new String[]{"code", "stage", "pointer", "limit"},
                Arrays.stream(components).map(component -> component.getName()).toArray(String[]::new)
        );
        assertArrayEquals(
                new Class<?>[]{
                        AssetAcceptanceAuthority.FailureCode.class,
                        AssetAcceptanceAuthority.FailureStage.class,
                        String.class,
                        Optional.class
                },
                Arrays.stream(components).map(component -> component.getType()).toArray(Class<?>[]::new)
        );
        assertFalse(AssetAcceptanceAuthority.Admitted.class.isRecord());
        assertTrue(Modifier.isFinal(AssetAcceptanceAuthority.Admitted.class.getModifiers()));
    }

    @Test
    void freezesDescriptorRecordShapes() {
        var image = AssetAcceptanceAuthority.ImageDescriptor.class.getRecordComponents();
        assertArrayEquals(
                new String[]{
                        "encodedWidthPx",
                        "encodedHeightPx",
                        "orientation",
                        "logicalWidthPx",
                        "logicalHeightPx",
                        "frameCount",
                        "colorEncoding"
                },
                Arrays.stream(image).map(component -> component.getName()).toArray(String[]::new)
        );
        var font = AssetAcceptanceAuthority.FontDescriptor.class.getRecordComponents();
        assertArrayEquals(
                new String[]{"faceIndex", "flavor", "unitsPerEm"},
                Arrays.stream(font).map(component -> component.getName()).toArray(String[]::new)
        );
        assertArrayEquals(
                new AssetAcceptanceAuthority.Orientation[]{
                        AssetAcceptanceAuthority.Orientation.IDENTITY,
                        AssetAcceptanceAuthority.Orientation.MIRROR_HORIZONTAL,
                        AssetAcceptanceAuthority.Orientation.ROTATE_180,
                        AssetAcceptanceAuthority.Orientation.MIRROR_VERTICAL,
                        AssetAcceptanceAuthority.Orientation.TRANSPOSE,
                        AssetAcceptanceAuthority.Orientation.ROTATE_90_CW,
                        AssetAcceptanceAuthority.Orientation.TRANSVERSE,
                        AssetAcceptanceAuthority.Orientation.ROTATE_270_CW
                },
                AssetAcceptanceAuthority.Orientation.values()
        );
        assertArrayEquals(
                new AssetAcceptanceAuthority.ColorEncoding[]{
                        AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT
                },
                AssetAcceptanceAuthority.ColorEncoding.values()
        );
        assertArrayEquals(
                new AssetAcceptanceAuthority.FontFlavor[]{
                        AssetAcceptanceAuthority.FontFlavor.TRUETYPE_GLYF,
                        AssetAcceptanceAuthority.FontFlavor.CFF
                },
                AssetAcceptanceAuthority.FontFlavor.values()
        );
    }
}
