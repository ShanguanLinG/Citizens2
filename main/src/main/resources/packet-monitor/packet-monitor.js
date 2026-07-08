(function () {
    var token = new URLSearchParams(window.location.search).get("token") || "";
    if (!token && window.location.pathname.indexOf("/t/") === 0) {
        token = decodeURIComponent(window.location.pathname.substring(3));
    }
    var ids = {};

    ["mode", "pps", "bps", "bpsUnit", "npcs", "players", "summary", "chart", "npcPackets", "npcReceivers",
            "topNPCs", "topPackets", "topReceivers", "resetButton", "reportButton"].forEach(function (id) {
        ids[id] = document.getElementById(id);
    });

    function escapeHtml(value) {
        return String(value == null ? "" : value).replace(/[&<>"]/g, function (ch) {
            return { "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" }[ch];
        });
    }

    function kib(bytes) {
        return (Number(bytes || 0) / 1024).toFixed(1);
    }

    function byteRate(bytes) {
        var value = Number(bytes || 0);
        var units = ["B/s", "KiB/s", "MiB/s", "GiB/s"];
        var unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return {
            value: unit === 0 ? String(Math.round(value)) : value.toFixed(1),
            unit: units[unit]
        };
    }

    function rowClass(name) {
        return name && name.indexOf("_") >= 0 ? "type" : "name";
    }

    function renderRows(target, rows, emptyColumns) {
        if (!rows || rows.length === 0) {
            target.innerHTML = "<tr><td class=\"empty\" colspan=\"" + emptyColumns + "\">暂无数据</td></tr>";
            return;
        }
        target.innerHTML = rows.map(function (item) {
            var name = escapeHtml(item.name);
            return "<tr><td class=\"" + rowClass(item.name) + "\">" + name + "</td>"
                    + "<td class=\"num\">" + Number(item.packets || 0) + "</td>"
                    + "<td class=\"num\">" + kib(item.bytes) + "</td></tr>";
        }).join("");
    }

    function renderByteRows(target, rows) {
        if (!rows || rows.length === 0) {
            target.innerHTML = "<tr><td class=\"empty\" colspan=\"2\">暂无数据</td></tr>";
            return;
        }
        target.innerHTML = rows.map(function (item) {
            var name = escapeHtml(item.name);
            return "<tr><td class=\"" + rowClass(item.name) + "\">" + name + "</td>"
                    + "<td class=\"num\">" + kib(item.bytes) + "</td></tr>";
        }).join("");
    }

    function renderChart(series) {
        var values = series || [];
        var max = values.reduce(function (prev, value) {
            return Math.max(prev, value);
        }, 1);
        ids.chart.innerHTML = values.map(function (value) {
            var height = Math.max(2, Math.round(value / max * 100));
            return "<div class=\"bar\" style=\"height:" + height + "%\" title=\"" + value + " 个包\"></div>";
        }).join("");
    }

    function setMode(snapshot) {
        var mode = escapeHtml(snapshot.captureMode || "limited");
        var accuracy = escapeHtml(snapshot.accuracyMode || "detailed estimate");
        var remaining = Number(snapshot.remainingSeconds || 0);
        ids.mode.innerHTML = "<span class=\"ok\">" + mode + "</span> / " + accuracy
                + (remaining > 0 ? " / " + remaining + " 秒后关闭" : "");
    }

    async function loadSnapshot() {
        try {
            var response = await fetch("/api/snapshot?token=" + encodeURIComponent(token), { cache: "no-store" });
            if (!response.ok) {
                ids.mode.innerHTML = "<span class=\"warn\">认证失败</span>";
                return;
            }
            var snapshot = await response.json();
            setMode(snapshot);
            ids.pps.textContent = snapshot.packetsPerSecond;
            var bandwidth = byteRate(snapshot.bytesPerSecond);
            ids.bps.textContent = bandwidth.value;
            ids.bpsUnit.textContent = " " + bandwidth.unit;
            ids.npcs.textContent = snapshot.activeNPCs;
            ids.players.textContent = snapshot.activeReceivers;
            ids.summary.textContent = "最近 " + snapshot.windowSeconds + " 秒："
                    + snapshot.windowPackets + " 个包 / " + kib(snapshot.windowBytes)
                    + " KiB；五分钟：" + snapshot.fiveMinutePackets + " 个包 / "
                    + kib(snapshot.fiveMinuteBytes) + " KiB";

            renderChart(snapshot.seriesPackets);
            renderRows(ids.topNPCs, snapshot.topNPCs, 3);
            renderRows(ids.topPackets, snapshot.topPacketTypes, 3);
            renderRows(ids.topReceivers, snapshot.topReceivers, 3);
            renderByteRows(ids.npcPackets, snapshot.selectedNpcPackets);
            renderByteRows(ids.npcReceivers, snapshot.selectedNpcReceivers);
        } catch (err) {
            ids.mode.innerHTML = "<span class=\"warn\">连接中断</span>";
        }
    }

    ids.reportButton.addEventListener("click", function () {
        var link = document.createElement("a");
        link.href = "/api/report?token=" + encodeURIComponent(token);
        link.download = "";
        document.body.appendChild(link);
        link.click();
        link.remove();
    });

    ids.resetButton.addEventListener("click", async function () {
        await fetch("/api/reset?token=" + encodeURIComponent(token), { method: "POST" });
        loadSnapshot();
    });

    loadSnapshot();
    window.setInterval(loadSnapshot, 1000);
})();
