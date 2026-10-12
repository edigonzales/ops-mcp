package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.adapter.openshift.OcJsonParser;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Erzeugt den OpenShift-Parser fuer den Fixturebetrieb.
 *
 * <p>Der Fixtureadapter verwendet <em>denselben</em> Parser wie der Live-Adapter. Nur die
 * Konstruktion ist hier tolerant: erwartet wird {@code (JsonMapper)}; fehlt dieser
 * Konstruktor, wird ein parameterloser Konstruktor verwendet. Ein fehlender Parser ist ein
 * erklaerter Fixturefehler und niemals ein stiller Ersatz durch eine eigene Auswertung.</p>
 */
final class OcJsonParserFactory {

    private OcJsonParserFactory() {
    }

    static OcJsonParser create(JsonMapper mapper) {
        try {
            Constructor<OcJsonParser> constructor = OcJsonParser.class.getConstructor(JsonMapper.class);
            return constructor.newInstance(mapper);
        } catch (NoSuchMethodException ex) {
            return createWithoutMapper();
        } catch (InvocationTargetException ex) {
            throw failure(ex.getCause() == null ? ex : ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw failure(ex);
        }
    }

    private static OcJsonParser createWithoutMapper() {
        try {
            Constructor<OcJsonParser> constructor = OcJsonParser.class.getConstructor();
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw failure(ex);
        }
    }

    private static IllegalStateException failure(Throwable cause) {
        return new IllegalStateException("Der OpenShift-Parser ist fuer den Fixturebetrieb nicht "
                + "konstruierbar. Erwartet wird ein Konstruktor (JsonMapper) oder (). Ursache: "
                + cause.getClass().getSimpleName(), cause);
    }
}
