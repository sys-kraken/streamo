"""
Network check for the Simulator test run on Codemagic: can this machine download YouTube's
video files at all? Asks YouTube for a video's streams the way the iPhone app does
(InnerTube "IOS" client), then fetches the first bytes of the picture file, the sound file and
an HLS piece with different User-Agents. Prints one line per attempt with the HTTP status,
bytes and seconds, so a playback failure can be told apart from a blocked network.

Run: python3 iosApp/Tests/youtube_check.py
"""
import json
import time
import urllib.error
import urllib.request

IOS_VERSION = "21.03.2"
IOS_UA = f"com.google.ios.youtube/{IOS_VERSION}(iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; US)"
AVPLAYER_UA = "AppleCoreMedia/1.0.0.22H124 (iPhone; U; CPU OS 18_7_2 like Mac OS X; en_us)"
SAFARI_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
VIDEOS = ["dQw4w9WgXcQ", "yT_AfrHbAyA"]


def player(video_id):
    body = {
        "context": {"client": {
            "clientName": "IOS", "clientVersion": IOS_VERSION, "deviceMake": "Apple",
            "deviceModel": "iPhone16,2", "osName": "iPhone", "osVersion": "18.7.2.22H124",
            "hl": "en", "gl": "US",
        }},
        "videoId": video_id, "contentCheckOk": True, "racyCheckOk": True,
    }
    req = urllib.request.Request(
        "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "User-Agent": IOS_UA,
                 "X-Goog-Api-Format-Version": "2", "X-YouTube-Client-Name": "5",
                 "X-YouTube-Client-Version": IOS_VERSION},
    )
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def fetch(label, url, ua, byte_range="bytes=0-99999"):
    headers = {"Range": byte_range} if byte_range else {}
    if ua:
        headers["User-Agent"] = ua
    start = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=15) as r:
            data = r.read(200_000)
            print(f"  {label}: HTTP {r.status}, {len(data)} bytes, {time.time() - start:.1f}s, type={r.headers.get('Content-Type')}")
            return data
    except urllib.error.HTTPError as e:
        print(f"  {label}: HTTP {e.code} ({e.reason}), {time.time() - start:.1f}s")
    except Exception as e:  # timeouts, resets
        print(f"  {label}: FAILED {type(e).__name__}: {e} after {time.time() - start:.1f}s")
    return b""


def main():
    try:
        ip = urllib.request.urlopen("https://api.ipify.org", timeout=10).read().decode()
        print(f"This machine's public IP: {ip}")
    except Exception as e:
        print(f"Couldn't look up the public IP: {e}")
    for vid in VIDEOS:
        p = player(vid)
        status = p.get("playabilityStatus", {})
        streams = p.get("streamingData", {})
        formats = streams.get("adaptiveFormats", [])
        print(f"{vid}: playability={status.get('status')} {status.get('reason') or ''} "
              f"hls={'yes' if streams.get('hlsManifestUrl') else 'no'} formats={len(formats)}")
        video = next((f for f in formats if 'avc1' in f.get('mimeType', '') and f.get('height', 0) <= 720 and 'url' in f), None)
        audio = next((f for f in formats if f.get('mimeType', '').startswith('audio/mp4') and 'url' in f), None)
        for ua_name, ua in [("iOS app UA", IOS_UA), ("AVPlayer UA", AVPLAYER_UA), ("Safari UA", SAFARI_UA), ("no UA", None)]:
            if video:
                fetch(f"picture {video['itag']} ({ua_name})", video["url"], ua)
            if audio:
                fetch(f"sound {audio['itag']} ({ua_name})", audio["url"], ua)
        if streams.get("hlsManifestUrl"):
            master = fetch("HLS playlist", streams["hlsManifestUrl"], IOS_UA, byte_range=None).decode(errors="replace")
            variants = [line for line in master.splitlines() if line.startswith("http")]
            if variants:
                media = fetch("HLS variant", variants[0], IOS_UA, byte_range=None).decode(errors="replace")
                pieces = [line for line in media.splitlines() if line.startswith("http")]
                if pieces:
                    for ua_name, ua in [("iOS app UA", IOS_UA), ("AVPlayer UA", AVPLAYER_UA)]:
                        fetch(f"HLS piece ({ua_name})", pieces[0], ua, byte_range=None)


if __name__ == "__main__":
    main()
