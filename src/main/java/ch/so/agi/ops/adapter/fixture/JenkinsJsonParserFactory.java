package ch.so.agi.ops.adapter.fixture;

import ch.so.agi.ops.adapter.jenkins.JenkinsJsonParser;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Erzeugt den Jenkins-Parser fuer den Fixturebetrieb.
 *
 * <p>Der Fixtureadapter verwendet <em>denselben</em> Parser wie der Live-Adapter. Nur die
 * Konstruktion ist hier tolerant: erwartet wird {@code (JsonMapper)}; fehlt dieser
 * Konstruktor, wird ein parameterloser Konstruktor verwendet. Ein fehlender Parser ist ein
 * erklaerter Fixturefehler und niemals ein stiller Ersatz durch eine eigene Auswertung.</p>
 */
final class JenkinsJsonParserFactory {

    private JenkinsJsonParserFactory() {
    }

    static JenkinsJsonParser create(JsonMapper mapper) {
        try {
            Constructor<JenkinsJsonParser> constructor = JenkinsJsonParser.class.getConstructor(JsonMapper.class);
            return constructor.newInstance(mapper);
        } catch (NoSuchMethodException ex) {
            return createWithoutMapper();
        } catch (InvocationTargetException ex) {
            throw failure(ex.getCause() == null ? ex : ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw failure(ex);
        }
    }

    private static JenkinsJsonParser createWithoutMapper() {
        try {
            Constructor<JenkinsJsonParser> constructor = JenkinsJsonParser.class.getConstructor();
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw failure(ex);
        }
    }

    private static IllegalStateException failure(Throwable cause) {
        return new IllegalStateException("Der Jenkins-Parser ist fuer den Fixturebetrieb nicht "
                + "konstruierbar. Erwartet wird ein Konstruktor (JsonMapper) oder (). Ursache: "
                + cause.getClass().getSimpleName(), cause);
    }
}
