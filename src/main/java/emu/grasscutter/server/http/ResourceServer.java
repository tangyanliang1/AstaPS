package emu.grasscutter.server.http;

import static emu.grasscutter.config.Configuration.RESOURCE_SERVER_INFO;

import emu.grasscutter.Grasscutter;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.io.File;

/**
 * Plaintext static file server for client resource downloads.
 *
 * <p>Serves the configured directory (default {@code ./public}) so the game client can fetch
 * {@code client_game_res/...} and {@code client_design_data/...} over HTTP. This replaces the
 * external {@code python -m http.server 8080 --directory public} step.
 *
 * <p>It runs as its own Javalin instance on its own port rather than sharing the dispatch server:
 * the dispatch server uses TLS and SSL redirects, which would rewrite the client's plaintext
 * resource requests and break the download.
 */
public final class ResourceServer {
    private Javalin javalin;

    /** Starts the server if it is enabled in the configuration. A no-op otherwise. */
    public void start() {
        if (!RESOURCE_SERVER_INFO.enabled) {
            return;
        }

        var directory = RESOURCE_SERVER_INFO.directory;
        var root = new File(directory);
        if (!root.isDirectory()) {
            Grasscutter.getLogger()
                    .warn(
                            "Resource server is enabled but the directory '{}' does not exist. "
                                    + "Resource downloads will fail until it is created.",
                            root.getAbsolutePath());
        }

        this.javalin =
                Javalin.create(
                        config -> {
                            config.showJavalinBanner = false;
                            config.staticFiles.add(
                                    staticFiles -> {
                                        staticFiles.hostedPath = "/";
                                        staticFiles.directory = directory;
                                        staticFiles.location = Location.EXTERNAL;
                                        // Resource manifests are plain text with no extension
                                        // (base_revision, res_versions_external, data_versions);
                                        // serving them as octet-stream is fine for the client.
                                        staticFiles.precompress = false;
                                    });
                        });

        this.javalin.start(RESOURCE_SERVER_INFO.bindAddress, RESOURCE_SERVER_INFO.bindPort);

        Grasscutter.getLogger()
                .info(
                        "Resource server started at {}:{} serving {}",
                        RESOURCE_SERVER_INFO.bindAddress,
                        RESOURCE_SERVER_INFO.bindPort,
                        root.getAbsolutePath());
    }

    /** Stops the server if it is running. */
    public void stop() {
        if (this.javalin != null) {
            this.javalin.stop();
            this.javalin = null;
        }
    }
}
