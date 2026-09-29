#!/usr/bin/env python3
"""A second pilot for testing shared challenges against a real room server.

The debug build's bot only exists with the in-memory fake. With ROOM_SERVER_URL set, this plays
the crew instead: it joins the room the phone shared, reports progress and claims outcomes over
the same protocol the app speaks (docs/shared-challenges.md "Protocol").

    tools/room_bot.py create '{"type": "ROUTE", "source": "CUSTOM", "name": "JFK to Boston", "originIata": "JFK", "destIata": "BOS"}'
    tools/room_bot.py show  ROOM42
    tools/room_bot.py join  ROOM42 --name "Bot Pilot"
    tools/room_bot.py put   ROOM42 --km 300          # distance pool: flown so far, in km
    tools/room_bot.py put   ROOM42 --progress 0.5 --leg 1   # race: fraction of the route
    tools/room_bot.py put   ROOM42 --members FR,DE    # set: visited members
    tools/room_bot.py put   ROOM42 --days 2 --day 2026-09-29   # streak
    tools/room_bot.py claim ROOM42                    # "I completed it"
    tools/room_bot.py fail  ROOM42 --by ABC123        # "the streak broke"
    tools/room_bot.py leave ROOM42

Each put sends the bot's whole snapshot, as the app does: fields not given keep their last value.
The bot's identity (pilot code and secret) and snapshots are kept in
$XDG_STATE_HOME/blocktime/room_bot.json (default ~/.local/state), so it stays the same pilot
across runs. --pilot NAME keeps several bots apart. The server URL is ROOM_SERVER_URL from the
environment or local.properties.
"""

import argparse
import json
import os
import pathlib
import secrets
import sys
import urllib.error
import urllib.request

ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
REPO = pathlib.Path(__file__).resolve().parent.parent


def server_url():
    url = os.environ.get("ROOM_SERVER_URL")
    if not url:
        props = REPO / "local.properties"
        if props.exists():
            for line in props.read_text().splitlines():
                if line.startswith("ROOM_SERVER_URL="):
                    url = line.split("=", 1)[1].strip()
    if not url:
        sys.exit("No ROOM_SERVER_URL in the environment or local.properties")
    return url.rstrip("/")


def state_file():
    base = pathlib.Path(os.environ.get("XDG_STATE_HOME", pathlib.Path.home() / ".local" / "state"))
    return base / "blocktime" / "room_bot.json"


def load_state():
    path = state_file()
    return json.loads(path.read_text()) if path.exists() else {}


def save_state(state):
    path = state_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, indent=2))


def code(length):
    return "".join(secrets.choice(ALPHABET) for _ in range(length))


def pilot(state, name):
    bots = state.setdefault("pilots", {})
    if name not in bots:
        bots[name] = {"code": code(6), "secret": code(32), "snapshots": {}}
        save_state(state)
    return bots[name]


def call(method, path, bot=None, body=None):
    request = urllib.request.Request(server_url() + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
    request.add_header("content-type", "application/json")
    if bot:
        request.add_header("x-pilot", bot["code"])
        request.add_header("authorization", "Bearer " + bot["secret"])
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


def show(room):
    outcome = room.get("outcome")
    print(f"{room['code']}  {room['definition'].get('type')}  \"{room['definition'].get('name')}\"  version {room['version']}")
    for p in room["participants"]:
        fields = {k: v for k, v in p.items() if k not in ("userCode", "username", "colorIndex", "updatedAt")}
        print(f"  {p['userCode']}  colour {p.get('colorIndex')}  {p.get('username', '')!r:24} {json.dumps(fields)}")
    if outcome:
        print(f"  outcome: {json.dumps(outcome)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["create", "show", "join", "put", "claim", "fail", "leave", "whoami"])
    parser.add_argument("room", nargs="?", help="the room code, or for create the definition as JSON")
    parser.add_argument("--pilot", default="bot", help="which bot identity to use")
    parser.add_argument("--name", help="the bot's pilot name")
    parser.add_argument("--km", type=float, help="distance flown, in km")
    parser.add_argument("--progress", type=float, help="route progress, 0 to 1")
    parser.add_argument("--leg", type=int, help="route leg index")
    parser.add_argument("--at", help="current airport (IATA)")
    parser.add_argument("--members", help="visited set members, comma-separated")
    parser.add_argument("--days", type=int, help="streak days")
    parser.add_argument("--day", help="last flown day, YYYY-MM-DD")
    parser.add_argument("--broken", action="store_true", help="mark the bot's streak dead")
    parser.add_argument("--by", help="who broke the streak (fail); defaults to the bot")
    args = parser.parse_args()

    state = load_state()
    bot = pilot(state, args.pilot)
    if args.command == "whoami":
        print(f"{args.pilot}: {bot['code']}")
        return
    if not args.room:
        parser.error("a room code is needed")
    if args.command == "create":
        snapshot = {"userCode": bot["code"], "username": args.name or "Bot Pilot"}
        status, room = call("POST", "/rooms", bot, {"definition": json.loads(args.room), "snapshot": snapshot})
        if status != 201:
            return print(status, room)
        bot["snapshots"][room["code"]] = snapshot
        save_state(state)
        return show(room)
    room_code = args.room.strip().upper()

    if args.command == "show":
        status, room = call("GET", f"/rooms/{room_code}")
        return show(room) if status == 200 else print(status, room)

    if args.command == "leave":
        status, body = call("DELETE", f"/rooms/{room_code}/participants/{bot['code']}", bot)
        return print(status, body or "")

    snapshot = bot["snapshots"].get(room_code, {"userCode": bot["code"], "username": "Bot Pilot"})
    if args.name:
        snapshot["username"] = args.name
    for arg, key in [("km", "distanceKm"), ("progress", "routeProgress"), ("leg", "legIndex"),
                     ("at", "positionIata"), ("days", "streakDays"), ("day", "lastFlownDay")]:
        if getattr(args, arg) is not None:
            snapshot[key] = getattr(args, arg)
    if args.members is not None:
        snapshot["visitedMembers"] = [m for m in args.members.split(",") if m]
    if args.broken:
        snapshot["streakAlive"] = False

    claim = None
    if args.command == "claim":
        claim = {"kind": "completed"}
    elif args.command == "fail":
        claim = {"kind": "failed", "brokenBy": args.by or bot["code"]}

    body = {"snapshot": snapshot}
    if claim:
        body["claim"] = claim
    status, room = call("PUT", f"/rooms/{room_code}/participants/{bot['code']}", bot, body)
    if status != 200:
        return print(status, room)
    bot["snapshots"][room_code] = snapshot
    save_state(state)
    show(room)


if __name__ == "__main__":
    main()
