package lu.uni.sdn.apigateway;

import com.google.common.base.Strings;
import org.onlab.util.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Dictionary;

/**
 * Component-config parsing shared by the modules' {@code XSettings.from(props)}.
 * Missing values give the default; invalid values give the default and a WARN.
 */
public final class Props {

    private static final Logger LOG = LoggerFactory.getLogger(Props.class);

    private Props() {
    }

    /**
     * Boolean property.
     *
     * @param p    properties (may be null)
     * @param name property name
     * @param def  default
     * @return value
     */
    public static boolean bool(Dictionary<?, ?> p, String name, boolean def) {
        return p == null ? def : Tools.isPropertyEnabled(p, name, def);
    }

    /**
     * String property; null/empty gives the default.
     *
     * @param p    properties (may be null)
     * @param name property name
     * @param def  default
     * @return value
     */
    public static String string(Dictionary<?, ?> p, String name, String def) {
        String v = p == null ? null : Strings.emptyToNull(Tools.get(p, name));
        return v == null ? def : v.trim();
    }

    /**
     * String property restricted to a set of values.
     *
     * @param p       properties (may be null)
     * @param name    property name
     * @param def     default
     * @param allowed allowed values
     * @return value
     */
    public static String choice(Dictionary<?, ?> p, String name, String def, String... allowed) {
        String v = string(p, name, def);
        if (Arrays.asList(allowed).contains(v)) {
            return v;
        }
        LOG.warn("Invalid {}={}, using {}", name, v, def);
        return def;
    }

    /**
     * Integer property that must be &gt; 0.
     *
     * @param p    properties (may be null)
     * @param name property name
     * @param def  default
     * @return value
     */
    public static int positiveInt(Dictionary<?, ?> p, String name, int def) {
        String v = p == null ? null : Tools.get(p, name);
        try {
            int i = v == null ? def : Integer.parseInt(v.trim());
            if (i > 0) {
                return i;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        LOG.warn("Invalid {}={}, using {}", name, v, def);
        return def;
    }

    /**
     * Double property that must be &gt; 0.
     *
     * @param p    properties (may be null)
     * @param name property name
     * @param def  default
     * @return value
     */
    public static double positiveDouble(Dictionary<?, ?> p, String name, double def) {
        return number(p, name, def, false);
    }

    /**
     * Double property that must be &gt;= 0.
     *
     * @param p    properties (may be null)
     * @param name property name
     * @param def  default
     * @return value
     */
    public static double nonNegativeDouble(Dictionary<?, ?> p, String name, double def) {
        return number(p, name, def, true);
    }

    private static double number(Dictionary<?, ?> p, String name, double def, boolean zeroOk) {
        String v = p == null ? null : Tools.get(p, name);
        try {
            double d = v == null ? def : Double.parseDouble(v.trim());
            if ((zeroOk ? d >= 0 : d > 0) && !Double.isInfinite(d)) {
                return d;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        LOG.warn("Invalid {}={}, using {}", name, v, def);
        return def;
    }
}
