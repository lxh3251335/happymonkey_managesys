package cn.tianshang.commerce.importing;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ImportRunner implements CommandLineRunner {

    private final SourceImporter importer;
    private final boolean enabled;
    private final String path;

    public ImportRunner(
        SourceImporter importer,
        @Value("${app.import-source}") boolean enabled,
        @Value("${app.source-seed}") String path
    ) {
        this.importer = importer;
        this.enabled = enabled;
        this.path = path;
    }

    public void run(String... args) throws Exception {
        if (enabled) importer.importSeed(Path.of(path));
    }
}
