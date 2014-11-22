package org.unchiujar.umbra.overlays;

import com.google.android.gms.maps.model.UrlTileProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;

public class OsmProvider extends UrlTileProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OsmProvider.class);
    private String baseUrl;

    public OsmProvider(int width, int height, String url) {
        super(width, height);
        this.baseUrl = url;
    }

    @Override
    public URL getTileUrl(int x, int y, int zoom) {
        try {
            return new URL(baseUrl.replace("{z}", "" + zoom).replace("{x}", "" + x).replace("{y}", "" + y));
        } catch (MalformedURLException e) {
            LOGGER.error("Malformed URL", e);
        }
        return null;
    }
}