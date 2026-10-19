package com.localshare.app.server;

import java.io.IOException;

/** Supplies the bundled web UI files (index.html, pdf.js ...). */
public interface AssetProvider {
    /** @return file bytes or null when the asset does not exist */
    byte[] get(String name) throws IOException;
}
