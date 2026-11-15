package dev.surge.catalog.importer;

import dev.surge.catalog.error.InvalidImportException;
import dev.surge.catalog.error.UploadTooLargeException;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin-only CSV import. The gateway already rejects non-admins on {@code /api/*}{@code /admin/**}; catalog's identity
 * chain and then AdminAccess repeat the check on the verified identity token as defence in depth. Paths stay
 * case-sensitive (Spring MVC's and the chain's default), matching the gateway, so {@code /api/catalog/ADMIN/...} is
 * simply a 404.
 */
@RestController
@RequestMapping("/api/catalog/admin/events")
public class ImportController {

    private final CsvEventImporter importer;

    public ImportController(CsvEventImporter importer) {
        this.importer = importer;
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importEvents(
            @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > CsvEventImporter.MAX_BYTES) {
            throw new UploadTooLargeException(CsvEventImporter.MAX_BYTES);
        }
        if (file.isEmpty()) {
            throw new InvalidImportException("The file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            return importer.importCsv(in);
        }
    }
}
