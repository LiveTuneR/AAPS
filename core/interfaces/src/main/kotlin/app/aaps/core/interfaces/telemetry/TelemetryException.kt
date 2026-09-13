package app.aaps.core.interfaces.telemetry

import org.json.JSONArray
import org.json.JSONObject

/** Stack locations only: exception messages may contain URLs, credentials or health payloads. */
object TelemetryException {
    fun fields(error: Throwable): JSONObject = JSONObject().put("errorType",error.javaClass.name)
        .put("stack",JSONArray().apply { error.stackTrace.take(40).forEach { frame ->
            put(JSONObject().put("class",frame.className).put("method",frame.methodName)
                .put("file",frame.fileName ?: JSONObject.NULL).put("line",frame.lineNumber))
        } })
}
