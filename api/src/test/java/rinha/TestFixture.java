package rinha;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import rinha.index.BuildIndex;

final class TestFixture {

    private TestFixture() {}

    static FraudScoreHandler createHandler() throws Exception {
        Path indexPath = ensureIndexBin();

        KdTreeLoader loader = new KdTreeLoader(indexPath);
        loader.startLoading();
        long deadlineNs = System.nanoTime() + 60_000_000_000L;
        while (!loader.isLoaded()) {
            Throwable err = loader.getLoadError();
            if (err != null) throw new IllegalStateException("kdtree load failed", err);
            if (System.nanoTime() > deadlineNs) throw new IllegalStateException("kdtree load timed out");
            Thread.sleep(50);
        }

        Normalizer norm = Normalizer.load(
            Path.of("src/main/resources/normalization.json"),
            Path.of("src/main/resources/mcc_risk.json"));

        return new FraudScoreHandler(loader, norm);
    }

    private static Path ensureIndexBin() throws Exception {
        Path indexPath = Path.of("target", "index.bin");
        if (Files.exists(indexPath) && Files.size(indexPath) > 0) return indexPath;

        Files.createDirectories(indexPath.getParent());

        Path refPath = Path.of("target", "test-references.json.gz");
        try (InputStream in = TestFixture.class.getResourceAsStream("/references.json.gz")) {
            if (in == null) throw new IllegalStateException(
                "references.json.gz not on test classpath — "
              + "run `mvn install` in ../indexer first");
            Files.copy(in, refPath, StandardCopyOption.REPLACE_EXISTING);
        }

        BuildIndex.main(new String[]{refPath.toString(), indexPath.toString()});
        return indexPath;
    }
}
