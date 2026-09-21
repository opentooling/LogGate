# LogGate user guide

LogGate is for the times a dashboard is the wrong tool: when you need **hours or
days of logs, for a set of pods, as files**, rather than a screen of search
results. You choose what you want, LogGate tells you what it will cost before
it starts, extracts it in the background, and hands you files you can open with
`zcat`, `jq`, `grep` or anything else.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="images/00-overview-dark.png">
  <img alt="LogGate with an export running across two namespaces and a finished one ready to download" src="images/00-overview-light.png">
</picture>

Every screenshot in this guide is of the real application, captured by
[`ui/screenshots/tour.spec.ts`](../ui/screenshots/tour.spec.ts) against a local
deployment with demo data.

## Contents

- [Signing in](#signing-in)
- [What you can export](#what-you-can-export)
- [Making an export](#making-an-export)
- [Estimate first](#estimate-first)
- [Your allowance](#your-allowance)
- [While it runs](#while-it-runs)
- [Downloading](#downloading)
- [What is in an export](#what-is-in-an-export)
- [When something goes wrong](#when-something-goes-wrong)
- [Themes and phones](#themes-and-phones)
- [Questions](#questions)

## Signing in

LogGate uses your organisation's single sign-on (Keycloak). Open LogGate and
you are sent to the usual sign-in page; there is no separate LogGate password.

<img alt="The Keycloak sign-in page" src="images/01-sign-in.png" width="640">

## What you can export

What is on offer depends on how your LogGate is set up. There are two modes.

### Your team's namespaces

In the default mode you can export from a namespace when **your team owns it**.
Ownership comes from a label on the namespace, and the label's value names the
group that may read it:

| Namespace label            | Group that may export it |
| -------------------------- | ------------------------ |
| `xyz.com/team: platform`   | `ad-platform-dev`        |
| `xyz.com/team: payments`   | `ad-payments-dev`        |

The label key and the group naming pattern are set by whoever runs LogGate, so
yours may differ. The list is read live from Kubernetes, so a namespace
relabelled a minute ago is already reflected. A namespace with no team label is
never offered to anyone.

If you are not in any owning group, LogGate says so rather than showing an
empty form:

<img alt="A user in no team is told there is nothing to export and who to ask" src="images/08-no-namespaces.png" width="720">

When your Loki holds more than one cluster's logs, this mode exports only from
the cluster LogGate runs in, which the form shows at the top. It is the only
cluster whose namespace owners LogGate can check: another cluster's
`platform-dev` may belong to someone else entirely.

### Every log, for people with the role

In open mode, **every namespace Loki holds logs for, from every cluster that
sends to it**, is available to anyone with LogGate's export role. Nobody's
team matters. It suits a central Loki collecting from many clusters, where
LogGate cannot see any of them directly.

<img alt="Open access: two clusters on offer, edge-eu chosen, its namespaces listed, and an estimate across both" src="images/10-open-access.png" width="360" align="right">

- **Clusters.** Tick the clusters you want. The namespaces listed follow what
  you tick: each cluster has its own. Ticking none means every cluster.
- **Namespaces.** Tick the ones you want, or none for every namespace in the
  clusters you chose. The form says which you are about to get, and the
  estimate shows the size before anything runs.
- **Budget.** With no teams to charge, your exports count against your own
  allowance.

The role is granted in Keycloak, usually to a group you are added to. Without
it you see nothing to export, and the page tells you what to ask for:

<br clear="right">

<img alt="Someone without the role is told which role, on which client, they need" src="images/11-no-role.png" width="720">

Losing the role also stops you downloading exports you made while you held it.

## Making an export

<img alt="The new export form with two namespaces selected and an estimate shown" src="images/03-estimate.png" width="360" align="right">

**Cluster.** Shown at the top when your Loki holds more than one cluster's
logs: fixed to one in the team mode, a choice in open mode.

**Namespaces.** Tick one or more. Each shows the team that owns it. In open
mode a long list gets a filter box, and ticking none means every namespace.

**Time range.** Use a preset or set *From* and *To* yourself. The length of
the range is shown next to the heading as you change it. The presets stop at
two days on purpose: exports are for bulk retrieval, and anything measured in
minutes is quicker to find in Grafana.

**Narrow it down.** Both are optional.

- **Pods** is a glob such as `api-*` or `checkout-*-worker`, not a regular
  expression. Empty means every pod in the namespaces you ticked.
- **Line contains** keeps only lines containing that text, matched literally.
  `timeout` finds `timeout`, not a pattern.

You never write a query. LogGate builds it from these choices and shows you
what it built (see [Estimate first](#estimate-first)).

A range longer than one export may cover is caught in the form, where you can
still change it, rather than after you submit:

<img alt="A three-day range is refused in the form with the two-day limit stated" src="images/05-range-limit.png" width="340">

<br clear="right">

## Estimate first

**Estimate first** asks Loki how much data your choices cover, **before any of
it is read**. This is the moment to find out an export is 40 GB rather than
after waiting for it.

The estimate card shows:

- **The size to download**, in large type. With a *Line contains* filter this
  is extrapolated from a sample of the range and says so.
- **What is read from Loki**, when a filter is set. Loki has to read the whole
  stream before the filter can drop lines, so this is larger than the download
  and is what quota is judged on.
- **How many files** the export will produce, and roughly how large each is.
- **A breakdown per namespace**, when you picked more than one.
- **What will be queried**: the exact LogQL generated from your choices,
  the size of the time windows it is extracted in, and the cap the job runs
  under. An export that grows past its cap stops rather than running on.
- **Whether it would be allowed.** If quota would refuse it, the card turns red
  and says why, for example that it is over the size allowed for one export. The
  final decision is made when you start it, so a refusal because too many
  exports are running may have cleared by then.

Once you have an estimate, the start button names the size: **Start export
(1.3 GB)**. Changing anything on the form clears the estimate, so the number
you see always matches the choices on screen.

You can start without estimating. The same checks run either way; estimating
only shows you the answer first.

## Your allowance

Open **Your allowance** at the bottom of the form to see the limits you are
working within, and how much of each is used:

<img alt="The allowance panel: a budget bar per team and the limits in words" src="images/04-allowance.png" width="340">

| Limit | What it means |
| --- | --- |
| **Team budget** | How much each of your teams may export over a rolling period. Spending ages out gradually, so there is no midnight reset to wait for. An export across two teams counts in full against both. |
| **Longest range** | The most time one export may cover. |
| **Largest export** | The most data one export may read from Loki. |
| **Exports running** | How many you may have in progress at once, and how many the whole platform may. |
| **Retention** | How long a finished export's files are kept before they are deleted. |

The numbers are set by whoever runs LogGate. The screenshots show the demo
settings: two days, 20 GB per export and per team per day, two exports each and
ten across the platform, kept for two days.

## While it runs

Exports run in the background. **You can close the page**: the export carries
on, and it will be in the list when you come back.

<img alt="A running export: progress bar, windows completed and time remaining" src="images/06-running.png" width="720">

A running export shows how many of its time windows are done, how many entries
and how much data it has written so far, its average speed, and roughly how
long is left. The time left is worked out from how long the finished windows
took, so it settles down after the first few.

Each export moves through these states:

| State | Meaning |
| --- | --- |
| `QUEUED` | Accepted, waiting to be planned. |
| `PLANNED` | Split into time windows, waiting for a worker. |
| `RUNNING` | Windows are being extracted. |
| `FINALIZING` | Every window is done; the manifest is being written. |
| `READY` | Files are available to download. |
| `FAILED` | Stopped, with the reason shown in plain English. |
| `CANCELLED` | Stopped at your request. Anything it had written is deleted. |
| `EXPIRED` | Its files have reached the end of their retention and been deleted. |

**Cancel** stops an export between pages of results, usually within seconds.

The list shows your eight most recent exports, with a button to show older ones.

## Downloading

<img alt="A ready export with its download options and its individual files listed" src="images/07-ready.png" width="720">

A ready export offers three ways to collect it. LogGate recommends one based
on the size, by highlighting it:

| Size | Recommended | Why |
| --- | --- | --- |
| Under 256 MB | **Download .zip** | Small enough for a browser. |
| 256 MB to 2 GB | Either | The script resumes if the connection drops. |
| 2 GB and over | **Download script** | An archive this size is not a browser download. |

**Download script** gives you a shell script. Run it anywhere with `curl`: it
downloads every file into a folder named after the export, resumes and retries
if the connection drops, and then checks each file against its SHA-256
checksum.

```bash
bash loggate-<export-id>-download.sh
```

**Download .zip** streams every file as one archive, through LogGate itself.
Convenient, but for large exports the script is faster and survives
interruptions.

**Files, individually** lists each file with a direct link, for when you only
want part of the range.

Two expiries apply, and the page tells you about both:

- **Download links expire shortly**: 30 minutes by default. They are signed, so
  they cannot be extended by editing them. If one has expired, reload the page
  or fetch the script again for fresh links.
- **The files are deleted** at the end of the retention period, shown under the
  download buttons as *These files are deleted in 2 days*. After that the export
  reads `EXPIRED`; run it again if you still need the data.

Access is checked again at download time. If you leave the owning team, you can
no longer fetch exports you made while you were in it; a link handed out before
you left still works until it expires.

If an export matched nothing, it says **No log lines matched** instead of
offering an empty download, and suggests what to check.

## What is in an export

| File | Contents |
| --- | --- |
| `manifest.json` | What the export is: the query and range it came from, the namespaces, entry and byte counts, and for every part its time window, size and SHA-256 checksum. It also lists any **caveats**, such as a range ending within the last 15 minutes, where some entries may still have been in transit to storage. |
| `000000.jsonl.gz`, `000001.jsonl.gz`, … | The logs, as gzipped JSON lines, one entry per line. The files are numbered in time order, and entries are in time order within each file. |

Each line is one log entry. This one, from the demo data, is spread over
several lines here to make it readable:

```json
{
  "timestamp": "1790012887203986968",
  "labels": {
    "namespace": "platform-dev",
    "pod": "platform-api-56f6bb49c6-q5zxw",
    "container": "api",
    "app": "platform-api",
    "service_name": "platform-api",
    "detected_level": "info",
    "instance": "platform-dev/platform-api-56f6bb49c6-q5zxw:api",
    "job": "loki.source.kubernetes.pods"
  },
  "line": "{\"ts\":\"2026-09-21T17:48:07+00:00\",\"level\":\"info\",\"msg\":\"request handled\",\"path\":\"/api/v1/things/1142801\",\"duration_ms\":51}\n"
}
```

- `timestamp` is nanoseconds since the Unix epoch, as a string so no precision
  is lost in tools that read numbers as floating point.
- `labels` are the Loki labels of the stream the line came from. Which labels
  there are depends on how your cluster ships its logs.
- `line` is the log line exactly as the pod wrote it, trailing newline included.
  If the pod logs JSON, as this one does, it is JSON inside a string, so
  `jq '.line | fromjson'` unpacks it.

Do not rely on the order of the keys within a line; it can vary.

Some ways to read them:

```bash
zcat *.jsonl.gz | jq -r .line                  # just the log lines
zcat *.jsonl.gz | jq -r 'select(.labels.pod | startswith("api-")) | .line'
zcat *.jsonl.gz | grep -i timeout               # plain text search
cat *.jsonl.gz > everything.jsonl.gz            # gzip files join into one valid file
```

## When something goes wrong

If an export fails, the reason is shown on the export in plain English.

| Shown as | What happened | What to do |
| --- | --- | --- |
| *This export produced more data than it was admitted for.* | It grew past the cap it was started with. | Narrow the time range or the pod pattern and try again. |
| *Loki stopped responding while this export was running.* | Loki kept failing after several retries. | It is safe to run it again. |
| *The export could not be written to storage.* | Object storage refused the files. | Tell whoever runs LogGate; this is not caused by your request. |

An export can also be **refused before it starts**, with the reason given at
once: a range or size over the limit, too many exports already running, or your
team's budget being used up. The message says which, and by how much.

## Themes and phones

<img alt="LogGate on a phone, in the dark theme" src="images/09-phone.png" width="240" align="right">

LogGate follows your system's light or dark setting. The button at the top of
the page switches between **System theme**, **Light** and **Dark**, and the
browser remembers your choice.

The page works at phone width. Starting a large export from your phone and
collecting it later from your laptop is fine: exports belong to you, not to the
browser that started them.

<br clear="right">

## Questions

**Why can't I see a namespace my team uses?**
In the team mode, either it has no team label, or its label names a team whose
group you are not in. LogGate never guesses; ask whoever owns the namespace to
label it or to add you to their group. In open mode, a namespace appears once
it has sent Loki a log line within the discovery window, a week by default.

**Why can I only choose one cluster?**
Your LogGate is in the team mode, which exports only from its own cluster
because that is the only one whose namespace owners it can check. Open mode
offers every cluster.

**Why is the download smaller than what is read from Loki?**
Because of your *Line contains* filter. Loki reads every line in the range to
find the ones that match, and quota counts that reading, because it is what
costs the platform.

**Why is a finished export bigger than the estimate?**
Logs keep arriving while an export runs, and each entry is written with its
timestamp and labels, which the estimate does not count. The manifest notes it
when an export came out larger than predicted.

**Can I write my own LogQL?**
No, on purpose. Choices made in the form can be sized, authorised and bounded
before anything runs; a free-form query cannot. For exploring logs
interactively, use Grafana.

**Is the data I download complete?**
For ranges that ended more than 15 minutes before the export, yes: every entry
Loki holds for the range. Entries that share the same nanosecond are all kept.
For a range reaching right up to the present, the manifest carries a caveat
that the most recent entries may still be arriving.

**Who can see what I exported?**
Only you can see your exports. Every export you start or cancel, every export
refused, and every attempt to reach a namespace you are not entitled to is
recorded in an audit log that the platform team can read.
