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

import java.util.ArrayList;
import java.util.List;

/**
 * Wire messages for ROTP multiplayer, exchanged as JSON over WebSocket.
 * Everything here must stay language-neutral: a browser client will
 * eventually consume the same protocol.
 */
public final class Messages {
    private Messages() { }

    /** client -> server: first message after connecting */
    public static class Hello {
        public int version;
        public String playerName;
    }

    /** server -> client: lobby roster, sent on every change */
    public static class Lobby {
        public List<Slot> slots = new ArrayList<>();
        public String message;
    }

    public static class Slot {
        public int empireId;
        public String playerName;
        public boolean connected;
    }

    /** server -> client: game created, you are this empire */
    public static class GameStarted {
        public int empireId;
    }

    /** client -> server: advance the turn (walking skeleton; later replaced by per-player ready flags) */
    public static class NextTurn {
    }

    /** server -> client: turn processing state */
    public static class TurnStatus {
        public boolean processing;
        public int turn;
        public String note;
    }

    /** server -> client */
    public static class Error {
        public String text;
    }
}
