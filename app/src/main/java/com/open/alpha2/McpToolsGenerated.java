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
            t.put("description", "List all built-in robot actions with their id, Chinese name, and English name. Useful for browsing what actions exist, but self.robot.play_action can now be called directly with a Chinese or English action name (fuzzy-matched server-side) - you do not need to call this first just to play a known action.");
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
            t.put("description", "Play a named built-in robot action/animation. Pass the action's Chinese or English name in natural language (e.g. \"舉左手\" or \"take left hand\", \"跳舞\" or \"dance\") - it will be matched against the robot's actual action list automatically. Only actions that exist in the robot's list can actually play, so if in doubt call self.robot.list_actions to see exact names first.");
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
            t.put("description", "Play a random filler movement to make the robot look more alive/expressive - use this when it feels natural to add a bit of physical animation, not necessarily tied to any specific emotion or reply content. Takes no arguments. IMPORTANT ordering rule: if the user's request also calls for a specific action via self.robot.play_action (e.g. they asked you to wave, dance, nod, etc.), call that specific action instead of (not in addition to) this random one for this turn - only reach for play_random_action when there is no other action already planned for this reply.");
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
            t.put("description", "Move a single servo to an angle. Servo ids and their valid angle ranges are specific to this robot's build - if unsure, use small movements first.");
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
                props.put("angle", p);
            }
            {
                JSONObject p = new JSONObject();
                p.put("type", "integer");
                p.put("description", "Movement duration in milliseconds. Default 1000.");
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
            t.put("description", "Set the head 5-mic LED ring. color: 1=red 2=green 3=blue 4=yellow 5=purple 6=cyan 7=white. brightness: 1 (dimmest) to 9 (brightest). preset: \"long\" (solid), \"flash\", \"breathe\", \"chase\", \"dual\", or \"stop\" (turns the ring off - color/brightness ignored).");
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
            t.put("description", "Set the eye 5-mic LED ring. Same color/brightness/preset semantics as self.robot.led_set_head (preset \"breathe\" is not available for the eye ring - only \"long\", \"flash\", \"chase\", \"dual\", \"stop\").");
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
            t.put("description", "Set the mouth LED. preset \"breathing\" pulses at the given speed (0-5000ms, 0=fastest); preset \"off\" turns it off. Note: this is driven automatically during XiaoZhi TTS playback, so calling it manually mid-conversation may fight with that.");
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
            t.put("description", "Read the last known PIR motion-sensor state (whether someone was last detected entering/present nearby). This is the most recently received event, not a live poll - if the sensor is disabled or no event has arrived yet, state will be \"unknown\".");
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
            t.put("description", "Read the last known ultrasonic sonar distance reading (centimeters) and the currently configured trigger threshold. This is the most recently received reading, not a live poll - if no reading has arrived yet, distance_cm will be -1.");
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
            t.put("description", "Take a photo with the robot's camera and get a description of what it sees. Optionally pass a specific question to focus the description on (e.g. \"how many people are there\"), otherwise a general description is returned.");
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
            t.put("description", "Get the text description for a photo previously captured via self.camera.take_photo. Call this after take_photo tells you to, using the uuid it gave you.");
            JSONObject s = new JSONObject();
            s.put("type", "object");
            JSONObject props = new JSONObject();
            {
                JSONObject p = new JSONObject();
                p.put("type", "string");
                p.put("description", "The uuid returned by self.camera.take_photo.");
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
            t.put("description", "Play a local music file on the robot. Pass the song name in natural language (it will be fuzzy-matched against the actual filenames) - call self.media.list_music first if unsure what is available.");
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
            t.put("description", "Search for live FM/internet radio stations from around the world (station name, e.g. a city, country, broadcaster or genre). Returns a list of matching stations - call self.media.play_radio with one of the returned names afterwards to actually play it.");
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
            t.put("description", "Play (or switch to) a live FM/internet radio station on the robot. Pass a station name in natural language - if it matches one of the stations returned by a previous self.media.search_radio call, that exact station is played; otherwise this will search for it directly. Switching straight to a different station is fine, no need to call self.media.stop_radio first.");
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
        return tools;
    }
}
