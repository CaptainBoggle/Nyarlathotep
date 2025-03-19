package com.cleanroommc.common;

import net.minecraft.network.NetworkManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

/**
 * Logging class for Nyarlathotep patches
 * Log to this whenever overriding default behaviour.
 */
public class NyarLog {
    private static final Logger log = LogManager.getLogger("Nyarlathotep");
    private static final Marker NYAR_MARKER = MarkerManager.getMarker("NYAR");
    private static final Marker JANK_MARKER = MarkerManager.getMarker("JANK").addParents(NYAR_MARKER);

    public static void jank(String msg, Throwable t) {
        log.warn(JANK_MARKER, msg, t);
    }

    public static void jank(Throwable t) {
        jank("Caught error", t);
    }

    public static void jank(String format, Object... data) {
        log.warn(JANK_MARKER, format, data);
    }

    public static void info(String format, Object... data) {
        log.info(NYAR_MARKER, format, data);
    }

    public static void debug(String format, Object... data) {
        log.debug(NYAR_MARKER, format, data);
    }
}
