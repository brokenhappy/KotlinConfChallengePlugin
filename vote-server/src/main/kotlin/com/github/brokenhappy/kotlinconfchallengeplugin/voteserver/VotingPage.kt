package com.github.brokenhappy.kotlinconfchallengeplugin.voteserver

import kotlinx.html.*

fun HTML.votingPage() {
    head {
        title("KotlinConf Vote")
        meta { charset = "utf-8" }
        meta { name = "viewport"; content = "width=device-width, initial-scale=1" }
        style {
            unsafe {
                raw("""
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: #1a1a2e; color: #eee;
                        display: flex; justify-content: center; align-items: center;
                        min-height: 100vh;
                    }
                    .container { text-align: center; width: 100%; max-width: 500px; padding: 24px; }
                    h1 { margin-bottom: 12px; font-size: 2rem; }
                    .status {
                        font-size: 1.1rem; margin-bottom: 32px; padding: 8px 16px;
                        border-radius: 8px; display: inline-block;
                    }
                    .status.open { background: #2e7d32; }
                    .status.closed { background: #c62828; }
                    .scores {
                        display: flex; justify-content: center; gap: 48px;
                        margin-bottom: 32px; font-size: 3rem; font-weight: bold;
                    }
                    .scores .red { color: #ef5350; }
                    .scores .blue { color: #42a5f5; }
                    .buttons { display: flex; gap: 16px; justify-content: center; }
                    button {
                        font-size: 1.4rem; padding: 16px 48px; border: none; border-radius: 12px;
                        cursor: pointer; font-weight: bold; color: #fff; transition: opacity 0.2s;
                    }
                    button:disabled { opacity: 0.3; cursor: not-allowed; }
                    .btn-red { background: #d32f2f; }
                    .btn-red:not(:disabled):hover { background: #b71c1c; }
                    .btn-blue { background: #1976d2; }
                    .btn-blue:not(:disabled):hover { background: #0d47a1; }
                """.trimIndent())
            }
        }
    }
    body {
        div("container") {
            h1 { +"KotlinConf Vote" }
            div("status closed") {
                id = "status"
                +"Connecting..."
            }
            div("scores") {
                span("red") { id = "redScore"; +"0" }
                span { +" - " }
                span("blue") { id = "blueScore"; +"0" }
            }
            div("buttons") {
                button(classes = "btn-red") {
                    id = "btnRed"
                    disabled = true
                    +"Vote Red"
                }
                button(classes = "btn-blue") {
                    id = "btnBlue"
                    disabled = true
                    +"Vote Blue"
                }
            }
        }
        script {
            unsafe {
                raw("""
                    const redScore = document.getElementById('redScore');
                    const blueScore = document.getElementById('blueScore');
                    const btnRed = document.getElementById('btnRed');
                    const btnBlue = document.getElementById('btnBlue');
                    const statusEl = document.getElementById('status');

                    function connect() {
                        const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
                        const ws = new WebSocket(proto + '//' + location.host + '/ws/notSoHiddenUrlBecauseItWillBeInOpenSourceRepo/status');
                        ws.onmessage = function(e) {
                            const d = JSON.parse(e.data);
                            redScore.textContent = d.number_of_red_votes;
                            blueScore.textContent = d.number_of_blue_votes;
                            btnRed.disabled = !d.session_active;
                            btnBlue.disabled = !d.session_active;
                            statusEl.textContent = d.session_active ? 'Voting is OPEN' : 'Voting is CLOSED';
                            statusEl.className = 'status ' + (d.session_active ? 'open' : 'closed');
                        };
                        ws.onclose = function() {
                            statusEl.textContent = 'Disconnected — reconnecting...';
                            statusEl.className = 'status closed';
                            btnRed.disabled = true;
                            btnBlue.disabled = true;
                            setTimeout(connect, 2000);
                        };
                    }
                    connect();

                    btnRed.addEventListener('click', function() { fetch('/vote/red', {method:'POST'}); });
                    btnBlue.addEventListener('click', function() { fetch('/vote/blue', {method:'POST'}); });
                """.trimIndent())
            }
        }
    }
}
