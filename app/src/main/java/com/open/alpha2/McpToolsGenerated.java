package com.open.alpha2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * MCP tool 定義（全部自動生成，切勿手改）。
 *
 * 來源：openapi/open-alpha2-openapi.yml（參數約束）＋
 * openapi/mcp-openapi-sync.yml（工具清單/description/alias/required）。
 * 再生：python scripts/generate-mcp-tools.py
 *
 * 約定：
 * - inputSchema 參數約束（type/enum/minimum/maximum/default）繼承 spec，
 *   與 ApiValidator.java 同一套規則；spec 加參數而 sync 未表態會生成失敗
 *   （見 generator 的 uncovered 檢查），逼兩邊對齊。
 * - alias（time_ms→time、enabled→on、distance_cm→distance、speed_ms→speed）
 *   在此只暴露 MCP 名；callTool() 側讀 MCP 名（見 XiaozhiBridge）。
 * - LED color/brightness 在 MCP 為 optional（preset=stop 免填，callTool 執行
 *   required_if），與舊手寫行為一致；spec 側維持必填。
 */
public final class McpToolsGenerated {
    private McpToolsGenerated() {}

    public static final int TOOL_COUNT = 22;

    public static JSONArray buildTools() throws JSONException {
        JSONArray tools = new JSONArray();

        // 1. self.robot.list_actions (/api/action/list GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.list_actions");
            t.put("description", "List all built-in robot actions (id, Chinese and English names). Only needed when unsure of the exact name - self.robot.play_action accepts names directly with server-side fuzzy matching.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 2. self.robot.play_action (/api/action/play GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.play_action");
            t.put("description", "Play a built-in robot action by Chinese or English name (fuzzy-matched server-side, e.g. \"跳舞\" or \"dance\"). Call self.robot.list_actions first if unsure of the exact name.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Chinese or English action name, e.g. \"舉左手\" or \"take left hand\".");
                props.put("name", p);
            }
            s.put("properties", props);
            s.put("required", new org.json.JSONArray().put("name"));
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 3. self.robot.stop_action (/api/action/stop POST)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.stop_action");
            t.put("description", "Stop whatever action is currently playing.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 4. self.robot.play_random_action (no HTTP mapping)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.play_random_action");
            t.put("description", "Play a random filler movement so the robot looks alive; takes no arguments. If this turn already plays a specific action via self.robot.play_action, skip this one instead of calling both.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 5. self.robot.servo_set_one (/api/servo/one GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.servo_set_one");
            t.put("description", "Move a single servo to an angle. Ids and valid ranges are specific to this robot's build - use small movements when unsure.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "Servo id (1-20).");
                p.put("minimum", 1);
                p.put("maximum", 20);
                props.put("id", p);
            }
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "Target angle in degrees.");
                p.put("minimum", 0);
                p.put("maximum", 255);
                props.put("angle", p);
            }
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "Movement duration in milliseconds. Default 1000.");
                p.put("minimum", 20);
                p.put("maximum", 32767);
                p.put("default", 1000);
                props.put("time_ms", p);
            }
            s.put("properties", props);
            s.put("required", new org.json.JSONArray().put("id").put("angle"));
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 6. self.robot.servo_set_all (/api/servo/all GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.servo_set_all");
            t.put("description", "Move all 20 servos at once to a full-body pose. angles must have exactly 20 comma-separated integers, one per servo id in order.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "20 comma-separated angle values, e.g. \"0,0,0,...\".");
                props.put("angles", p);
            }
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "Movement duration in milliseconds. Default 1000.");
                p.put("minimum", 20);
                p.put("maximum", 32767);
                p.put("default", 1000);
                props.put("time_ms", p);
            }
            s.put("properties", props);
            s.put("required", new org.json.JSONArray().put("angles"));
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 7. self.robot.led_set_head (/api/led/head/set GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.led_set_head");
            t.put("description", "Set the head LED ring. color 1=red 2=green 3=blue 4=yellow 5=purple 6=cyan 7=white; brightness 1-9; preset long/flash/breathe/chase/dual, or \"stop\" (off; color/brightness ignored).");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Effect preset. Default \"long\".");
                p.put("enum", new org.json.JSONArray().put("long").put("flash").put("breathe").put("chase").put("dual").put("stop"));
                p.put("default", "long");
                props.put("preset", p);
            }
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "1-7, required unless preset=stop.");
                p.put("minimum", 1);
                p.put("maximum", 7);
                props.put("color", p);
            }
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "1-9, required unless preset=stop.");
                p.put("minimum", 1);
                p.put("maximum", 9);
                props.put("brightness", p);
            }
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 8. self.robot.led_set_eye (/api/led/eye/set GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.led_set_eye");
            t.put("description", "Set the eye LED ring. Same color/brightness/preset as the head ring, except preset \"breathe\" is unavailable.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Effect preset. Default \"long\".");
                p.put("enum", new org.json.JSONArray().put("long").put("flash").put("chase").put("dual").put("stop"));
                p.put("default", "long");
                props.put("preset", p);
            }
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "1-7, required unless preset=stop.");
                p.put("minimum", 1);
                p.put("maximum", 7);
                props.put("color", p);
            }
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "1-9, required unless preset=stop.");
                p.put("minimum", 1);
                p.put("maximum", 9);
                props.put("brightness", p);
            }
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 9. self.robot.led_set_mouth (/api/led/mouth/set GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.led_set_mouth");
            t.put("description", "Set the mouth LED. preset \"breathing\" pulses at speed 0-5000ms (0=fastest); \"off\" turns it off. Driven automatically during TTS playback - manual calls mid-conversation may conflict.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Default \"breathing\".");
                p.put("enum", new org.json.JSONArray().put("breathing").put("off"));
                p.put("default", "breathing");
                props.put("preset", p);
            }
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "Breathing speed 0-5000ms, only used when preset=breathing. Default 0.");
                p.put("minimum", 0);
                p.put("maximum", 5000);
                p.put("default", 0);
                props.put("speed_ms", p);
            }
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 10. self.sensors.get_pir (no HTTP mapping)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.sensors.get_pir");
            t.put("description", "Read the last known PIR motion state (most recent event, not a live poll; \"unknown\" if disabled or nothing received yet).");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 11. self.sensors.set_pir_enabled (/api/pir/set GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.sensors.set_pir_enabled");
            t.put("description", "Turn the PIR motion sensor hardware on or off.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "boolean");
                p.put("description", "true=on, false=off.");
                props.put("enabled", p);
            }
            s.put("properties", props);
            s.put("required", new org.json.JSONArray().put("enabled"));
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 12. self.sensors.get_sonar (no HTTP mapping)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.sensors.get_sonar");
            t.put("description", "Read the last known sonar distance in cm plus the trigger threshold (most recent reading, not a live poll; -1 if none yet).");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 13. self.sensors.set_sonar_threshold (/api/servo/sonar GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.sensors.set_sonar_threshold");
            t.put("description", "Configure the sonar obstacle-trigger distance threshold in centimeters.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "cm.");
                p.put("minimum", 0);
                p.put("maximum", 100);
                props.put("distance_cm", p);
            }
            s.put("properties", props);
            s.put("required", new org.json.JSONArray().put("distance_cm"));
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 14. self.camera.take_photo (/api/camera/snapshot GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.camera.take_photo");
            t.put("description", "Take a photo and get a description of what the robot sees. Optionally pass a question to focus the description (e.g. \"how many people are there\").");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Optional question to focus the photo description on.");
                props.put("question", p);
            }
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 15. self.camera.image_to_text (no HTTP mapping)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.camera.image_to_text");
            t.put("description", "See the most recently captured photo again (from self.camera.take_photo) - the image is attached directly, no follow-up needed.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Accepted but not required; the most recent photo is always returned.");
                props.put("uuid", p);
            }
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 16. self.robot.speak (/api/speech/tts GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.robot.speak");
            t.put("description", "Speak a short phrase out loud through the robot's TTS.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Text to speak.");
                props.put("text", p);
            }
            s.put("properties", props);
            s.put("required", new org.json.JSONArray().put("text"));
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 17. self.media.list_music (/api/audio/local_music/list GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.media.list_music");
            t.put("description", "List all local music files available to play on the robot.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 18. self.media.play_music (/api/audio/local_music/play GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.media.play_music");
            t.put("description", "Play a local music file by song name (fuzzy-matched to filenames). Call self.media.list_music first if unsure what's available.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Song name or filename to play (fuzzy-matched).");
                props.put("name", p);
            }
            s.put("properties", props);
            s.put("required", new org.json.JSONArray().put("name"));
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 19. self.media.stop_music (/api/audio/local_music/stop GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.media.stop_music");
            t.put("description", "Stop whatever local music track is currently playing.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 20. self.media.search_radio (/api/audio/radio/search GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.media.search_radio");
            t.put("description", "Search worldwide radio stations by name, city, country, broadcaster or genre. Returns matches - then call self.media.play_radio with one of the names to play it.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Search text, e.g. \"BBC\", \"jazz\", \"Tokyo\", \"香港電台\".");
                props.put("query", p);
            }
            s.put("properties", props);
            s.put("required", new org.json.JSONArray().put("query"));
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 21. self.media.play_radio (/api/audio/radio/play GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.media.play_radio");
            t.put("description", "Play (or switch to) a radio station by name. Matches a previous self.media.search_radio result when possible, otherwise searches directly. No need to call self.media.stop_radio first.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "Station name, e.g. \"BBC World Service\" or \"香港電台第一台\".");
                props.put("name", p);
            }
            s.put("properties", props);
            s.put("required", new org.json.JSONArray().put("name"));
            t.put("inputSchema", s);
            tools.put(t);
        }

        // 22. self.media.stop_radio (/api/audio/radio/stop GET)
        {
            JSONObject t = new JSONObject();
            t.put("name", "self.media.stop_radio");
            t.put("description", "Stop whatever radio station is currently playing.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            s.put("properties", props);
            t.put("inputSchema", s);
            tools.put(t);
        }
        // 2026-09-09：再生漏改 TOOL_COUNT 即靜默錯，runtime 斷言釘死。
        if (tools.length() != TOOL_COUNT) throw new IllegalStateException(
                "TOOL_COUNT=" + TOOL_COUNT + " but built " + tools.length());
        return tools;
    }
}
