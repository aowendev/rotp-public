/*
 * Copyright 2015-2020 Ray Fowler
 *
 * Licensed under the GNU General Public License, Version 3 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rotp.mp.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;

/**
 * JSON envelope encoding for multiplayer messages:
 *   {"t": "<type>", "d": { ...payload... }}
 *
 * Message classes are registered by short type name so both ends (and a
 * future browser client) agree on the wire vocabulary.
 */
public final class Protocol {
    public static final int VERSION = 1;

    private static final Gson GSON = new Gson();
    private static final Map<String, Class<?>> BY_NAME = new HashMap<>();
    private static final Map<Class<?>, String> BY_CLASS = new HashMap<>();

    static {
        register("hello",          Messages.Hello.class);
        register("lobby",          Messages.Lobby.class);
        register("joined",         Messages.Joined.class);
        register("startGame",      Messages.StartGame.class);
        register("gameStarted",    Messages.GameStarted.class);
        register("gameOver",       Messages.GameOver.class);
        register("raceOptions",    Messages.RaceOptions.class);
        register("sizeOptions",    Messages.SizeOptions.class);
        register("difficultyOptions", Messages.DifficultyOptions.class);
        register("pickRace",       Messages.PickRace.class);
        register("ready",          Messages.Ready.class);
        register("turnStatus",     Messages.TurnStatus.class);
        register("error",          Messages.Error.class);
        register("view",           PlayerView.class);
        register("setColonyAlloc", Messages.SetColonyAllocations.class);
        register("previewColony",  Messages.PreviewColony.class);
        register("colonyPreview",  Messages.ColonyPreview.class);
        register("setColonyLock",  Messages.SetColonyLock.class);
        register("setTechAlloc",   Messages.SetTechAllocations.class);
        register("setResearchChoice", Messages.SetResearchChoice.class);
        register("deployFleet",    Messages.DeployFleet.class);
        register("sendTransports", Messages.SendTransports.class);
        register("abortTransports",Messages.AbortTransports.class);
        register("colonize",       Messages.Colonize.class);
        register("designCatalog",  Messages.DesignCatalog.class);
        register("createDesign",   Messages.CreateDesign.class);
        register("scrapDesign",    Messages.ScrapDesign.class);
        register("setShipBuild",   Messages.SetShipBuild.class);
        register("setSpySpending", Messages.SetSpySpending.class);
        register("setSpyMission",  Messages.SetSpyMission.class);
        register("setSecurity",    Messages.SetSecurity.class);
        register("diploOffer",     Messages.DiploOffer.class);
        register("breakTreaty",    Messages.BreakTreaty.class);
        register("declareWar",     Messages.DeclareWar.class);
        register("diploReply",     Messages.DiploReply.class);
        register("cmdResult",      Messages.CommandResult.class);
        register("notifications",  Messages.Notifications.class);
    }

    private Protocol() { }

    private static void register(String name, Class<?> cls) {
        BY_NAME.put(name, cls);
        BY_CLASS.put(cls, name);
    }

    public static String encode(Object msg) {
        String type = BY_CLASS.get(msg.getClass());
        if (type == null)
            throw new IllegalArgumentException("Unregistered message class: "+msg.getClass());
        JsonObject env = new JsonObject();
        env.addProperty("t", type);
        env.add("d", GSON.toJsonTree(msg));
        return GSON.toJson(env);
    }

    /** returns the decoded message object, or null for unknown types */
    public static Object decode(String json) {
        JsonObject env = JsonParser.parseString(json).getAsJsonObject();
        String type = env.get("t").getAsString();
        Class<?> cls = BY_NAME.get(type);
        if (cls == null)
            return null;
        JsonObject data = env.has("d") && env.get("d").isJsonObject()
            ? env.getAsJsonObject("d") : new JsonObject();
        return GSON.fromJson(data, cls);
    }
}
