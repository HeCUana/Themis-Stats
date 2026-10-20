import sqlite3, sys, os

db = r"C:\Users\Administrator\Desktop\mod\Themis Stats\server\world\themisstats\stats.db"
if not os.path.exists(db):
    print("database not found:", db); sys.exit(1)

con = sqlite3.connect(db)
cur = con.cursor()

def show(title, sql, args=()):
    print("\n=== %s ===" % title)
    try:
        cur.execute(sql, args)
        rows = cur.fetchall()
        if not rows:
            print("  (no rows)")
        for r in rows:
            print("  ", r)
    except Exception as e:
        print("  ERROR:", e)

show("schema_version", "SELECT value FROM meta WHERE key='schema_version'")
show("totals", "SELECT uuid, identity, metric, category, dimension, game_mode, value FROM total ORDER BY metric, identity")
show("session_metric", "SELECT session_id, metric, category, value FROM session_metric")
show("accounts", "SELECT uuid, current_name FROM account")
show("account_name", "SELECT uuid, name FROM account_name")
show("sessions", "SELECT id, uuid, identity, start_wall, end_wall, end_reason, actual_online_ms, collected_ms, complete, gap_note FROM session")
show("bot_profile", "SELECT * FROM bot_profile")
show("exclusion", "SELECT * FROM exclusion")
show("adjustment", "SELECT * FROM adjustment")
show("applied_batch", "SELECT * FROM applied_batch")
show("audit", "SELECT action, target, detail, operator FROM audit ORDER BY id")
show("tables", "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
con.close()
