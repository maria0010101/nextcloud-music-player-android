#!/usr/bin/env bash
# ImageMagick-only resize/composite workflow; keep the supplied artwork unchanged.
set -euo pipefail
cd "$(dirname "$0")/.."
src=tools/icon-source/Nextcloud_player.png
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Sample the artwork's empty upper background, not the old blue gradient.
magick "$src" -crop 650x200+300+110 +repage -alpha off \
    -resize 1296x1296! "$tmp/background.png"
# Discard transparent padding and stray pixels outside the original rounded tile.
magick -size 1144x1132 xc:black -fill white \
    -draw 'roundrectangle 26,26 1117,1105 250,250' -blur 0x8 "$tmp/mask.png"
magick "$src" -crop 1144x1132+52+69 +repage \
    "$tmp/mask.png" -alpha off -compose CopyOpacity -composite \
    -resize 864x864! "$tmp/artwork.png"
# 108dp adaptive canvas: the supplied tile occupies the central 72dp, with
# matching artwork background extending through the full bleed/parallax area.
magick "$tmp/background.png" "$tmp/artwork.png" -gravity center \
    -compose Over -composite -alpha off "$tmp/full-bleed.png"

for spec in mdpi:48:108 hdpi:72:162 xhdpi:96:216 xxhdpi:144:324 xxxhdpi:192:432; do
    IFS=: read -r density legacy adaptive <<< "$spec"
    dir="app/src/main/res/mipmap-$density"
    magick "$tmp/full-bleed.png" -resize "${adaptive}x${adaptive}" "$dir/ic_launcher_foreground.png"
    # Pre-adaptive fallback uses the same 72dp visible window, without padding.
    magick "$tmp/full-bleed.png" -crop 864x864+216+216 +repage \
        -resize "${legacy}x${legacy}" "$dir/ic_launcher.png"
    cp "$dir/ic_launcher.png" "$dir/ic_launcher_round.png"
done
