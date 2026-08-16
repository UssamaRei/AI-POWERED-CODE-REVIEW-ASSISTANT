/**
 * AI Code Review Assistant — Dashboard Application Logic
 */

// Global Chart Instances
let trendChartInstance = null;
let categoryChartInstance = null;
let severityChartInstance = null;

// Mock / Sample Data (Used if summary.json is not yet populated or viewed locally)
const DEMO_DATA = {
    totalPrsReviewed: 6,
    averageQualityScore: 88,
    averageGrade: "B",
    totalFilesReviewed: 18,
    totalBugsPrevented: 32,
    totalSeverities: {
        critical: 11,
        warning: 21,
        suggestion: 8,
        nitpick: 4
    },
    totalCategories: {
        "security": 9,
        "resource-leak": 8,
        "performance": 6,
        "null-safety": 5,
        "error-handling": 4
    },
    history: [
        {
            prNumber: 6,
            repository: "UssamaRei/AI-POWERED-CODE-REVIEW-ASSISTANT",
            timestamp: "2026-08-16T22:20:00Z",
            qualityScore: 92,
            grade: "A",
            filesReviewed: 2,
            languages: ["Java", "Python"],
            severities: { critical: 1, warning: 2, suggestion: 1, nitpick: 0 },
            categories: { "null-safety": 1, "resource-leak": 2, "error-handling": 1 },
            files: [
                { path: "src/main/java/dev/codereviewer/util/SampleReportService.java", language: "Java", status: "reviewed", findingsCount: 2 },
                { path: "scripts/deploy.py", language: "Python", status: "reviewed", findingsCount: 2 }
            ]
        },
        {
            prNumber: 5,
            repository: "UssamaRei/AI-POWERED-CODE-REVIEW-ASSISTANT",
            timestamp: "2026-08-15T19:40:00Z",
            qualityScore: 78,
            grade: "C",
            filesReviewed: 3,
            languages: ["Java", "C#"],
            severities: { critical: 3, warning: 4, suggestion: 1, nitpick: 1 },
            categories: { "security": 4, "resource-leak": 2, "performance": 2 },
            files: [
                { path: "src/main/java/dev/codereviewer/util/UserManager.java", language: "Java", status: "reviewed", findingsCount: 5 },
                { path: "Controllers/AuthController.cs", language: "C#", status: "reviewed", findingsCount: 3 }
            ]
        },
        {
            prNumber: 4,
            repository: "UssamaRei/AI-POWERED-CODE-REVIEW-ASSISTANT",
            timestamp: "2026-08-14T14:15:00Z",
            qualityScore: 100,
            grade: "A+",
            filesReviewed: 4,
            languages: ["TypeScript", "React"],
            severities: { critical: 0, warning: 0, suggestion: 2, nitpick: 1 },
            categories: { "naming": 2, "documentation": 1 },
            files: [
                { path: "frontend/src/components/Header.tsx", language: "TypeScript (React)", status: "reviewed", findingsCount: 1 },
                { path: "frontend/src/utils/format.ts", language: "TypeScript", status: "reviewed", findingsCount: 2 }
            ]
        },
        {
            prNumber: 3,
            repository: "UssamaRei/AI-POWERED-CODE-REVIEW-ASSISTANT",
            timestamp: "2026-08-13T11:00:00Z",
            qualityScore: 84,
            grade: "B",
            filesReviewed: 3,
            languages: ["Go", "Java"],
            severities: { critical: 2, warning: 5, suggestion: 1, nitpick: 0 },
            categories: { "concurrency": 3, "performance": 2, "null-safety": 2 },
            files: [
                { path: "server/worker.go", language: "Go", status: "reviewed", findingsCount: 4 },
                { path: "src/main/java/dev/codereviewer/review/FileReviewTask.java", language: "Java", status: "reviewed", findingsCount: 4 }
            ]
        },
        {
            prNumber: 2,
            repository: "UssamaRei/AI-POWERED-CODE-REVIEW-ASSISTANT",
            timestamp: "2026-08-12T09:30:00Z",
            qualityScore: 89,
            grade: "B",
            filesReviewed: 3,
            languages: ["Java", "Rust"],
            severities: { critical: 2, warning: 4, suggestion: 1, nitpick: 1 },
            categories: { "resource-leak": 3, "security": 2, "error-handling": 2 },
            files: [
                { path: "engine/src/lib.rs", language: "Rust", status: "reviewed", findingsCount: 3 },
                { path: "src/main/java/dev/codereviewer/util/UserManager.java", language: "Java", status: "reviewed", findingsCount: 4 }
            ]
        },
        {
            prNumber: 1,
            repository: "UssamaRei/AI-POWERED-CODE-REVIEW-ASSISTANT",
            timestamp: "2026-08-10T16:20:00Z",
            qualityScore: 85,
            grade: "B",
            filesReviewed: 3,
            languages: ["Java"],
            severities: { critical: 3, warning: 6, suggestion: 2, nitpick: 1 },
            categories: { "security": 3, "resource-leak": 3, "performance": 2, "error-handling": 2 },
            files: [
                { path: "src/main/java/dev/codereviewer/util/UserManager.java", language: "Java", status: "reviewed", findingsCount: 6 },
                { path: "src/main/java/dev/codereviewer/util/SampleReportService.java", language: "Java", status: "reviewed", findingsCount: 5 }
            ]
        }
    ]
};

let currentData = null;

// Initialize Dashboard
document.addEventListener("DOMContentLoaded", () => {
    initEventListeners();
    loadDashboardData();
});

function initEventListeners() {
    document.getElementById("btn-reload")?.addEventListener("click", () => loadDashboardData());
    document.getElementById("btn-toggle-demo")?.addEventListener("click", () => {
        currentData = DEMO_DATA;
        updateStatus(false, "Demo Dataset");
        renderDashboard(DEMO_DATA);
    });

    document.getElementById("json-file-input")?.addEventListener("change", handleFileUpload);
    document.getElementById("table-search")?.addEventListener("input", handleSearch);

    document.getElementById("btn-close-modal")?.addEventListener("click", closeModal);
    document.getElementById("pr-modal")?.addEventListener("click", (e) => {
        if (e.target.id === "pr-modal") closeModal();
    });
}

/**
 * Attempts to load live `.reviews/summary.json`, otherwise gracefully falls back to DEMO_DATA.
 */
async function loadDashboardData() {
    try {
        const response = await fetch("../.reviews/summary.json");
        if (!response.ok) throw new Error("No live summary.json found");
        const data = await response.json();
        currentData = data;
        updateStatus(true, "Live Repository Data");
        renderDashboard(data);
    } catch (e) {
        console.info("Using embedded demo dataset (run GitHub Action to generate live .reviews/summary.json)");
        currentData = DEMO_DATA;
        updateStatus(false, "Demo Dataset (Run Action to Populate)");
        renderDashboard(DEMO_DATA);
    }
}

function updateStatus(isLive, label) {
    const dot = document.getElementById("source-indicator");
    const text = document.getElementById("source-label");
    if (dot) dot.className = "status-dot " + (isLive ? "live" : "demo");
    if (text) text.textContent = label;
}

/**
 * Main render function
 */
function renderDashboard(data) {
    renderKPIs(data);
    renderCharts(data);
    renderHotspots(data);
    renderHistoryTable(data.history || []);
}

function renderKPIs(data) {
    const score = data.averageQualityScore || 100;
    const grade = data.averageGrade || "A+";

    const elScore = document.getElementById("kpi-health-score");
    const elGrade = document.getElementById("kpi-grade");
    const elProgress = document.getElementById("health-progress");
    const elTotalPrs = document.getElementById("kpi-total-prs");
    const elFiles = document.getElementById("kpi-files-reviewed");
    const elBugs = document.getElementById("kpi-bugs-prevented");
    const elCrit = document.getElementById("kpi-critical-count");
    const elWarn = document.getElementById("kpi-warning-count");

    if (elScore) elScore.textContent = score;
    if (elGrade) {
        elGrade.textContent = grade;
        elGrade.className = "grade-badge grade-" + grade.charAt(0).toLowerCase();
    }
    if (elProgress) elProgress.style.width = score + "%";
    if (elTotalPrs) elTotalPrs.textContent = data.totalPrsReviewed || 0;
    if (elFiles) elFiles.textContent = `📁 ${data.totalFilesReviewed || 0} files analyzed`;
    if (elBugs) elBugs.textContent = data.totalBugsPrevented || 0;

    const criticals = data.totalSeverities?.critical || 0;
    const warnings = data.totalSeverities?.warning || 0;
    if (elCrit) elCrit.textContent = `🔴 ${criticals} Critical`;
    if (elWarn) elWarn.textContent = `🟡 ${warnings} Warning`;

    // Extract all unique languages
    const languages = new Set();
    (data.history || []).forEach(pr => (pr.languages || []).forEach(l => languages.add(l)));
    const elLangCount = document.getElementById("kpi-languages-count");
    const elLangList = document.getElementById("kpi-languages-list");

    if (elLangCount) elLangCount.textContent = Math.max(1, languages.size);
    if (elLangList) {
        elLangList.innerHTML = "";
        languages.forEach(l => {
            const span = document.createElement("span");
            span.className = "lang-tag";
            span.textContent = l;
            elLangList.appendChild(span);
        });
    }
}

function renderCharts(data) {
    renderTrendChart(data.history || []);
    renderCategoryChart(data.totalCategories || {});
    renderSeverityChart(data.totalSeverities || {});
}

function renderTrendChart(history) {
    const ctx = document.getElementById("trendChart");
    if (!ctx) return;

    if (trendChartInstance) trendChartInstance.destroy();

    // Reverse history to show chronological progression
    const reversed = [...history].reverse();
    const labels = reversed.map(h => `PR #${h.prNumber}`);
    const scores = reversed.map(h => h.qualityScore);
    const bugs = reversed.map(h => (h.severities?.critical || 0) + (h.severities?.warning || 0));

    trendChartInstance = new Chart(ctx, {
        type: 'line',
        data: {
            labels: labels,
            datasets: [
                {
                    label: 'Health Score (0-100%)',
                    data: scores,
                    borderColor: '#00e5ff',
                    backgroundColor: 'rgba(0, 229, 255, 0.1)',
                    borderWidth: 3,
                    fill: true,
                    tension: 0.35,
                    yAxisID: 'y'
                },
                {
                    label: 'Bugs / Flaws Found',
                    data: bugs,
                    borderColor: '#ef4444',
                    backgroundColor: 'rgba(239, 68, 68, 0.7)',
                    type: 'bar',
                    borderRadius: 4,
                    yAxisID: 'y1'
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: { labels: { color: '#94a3b8', font: { family: 'Inter', size: 12 } } }
            },
            scales: {
                x: {
                    grid: { color: 'rgba(255, 255, 255, 0.05)' },
                    ticks: { color: '#64748b', font: { family: 'Inter' } }
                },
                y: {
                    min: 0,
                    max: 100,
                    grid: { color: 'rgba(255, 255, 255, 0.05)' },
                    ticks: { color: '#64748b' },
                    title: { display: true, text: 'Score %', color: '#00e5ff' }
                },
                y1: {
                    position: 'right',
                    min: 0,
                    grid: { drawOnChartArea: false },
                    ticks: { color: '#ef4444', stepSize: 1 },
                    title: { display: true, text: 'Issues', color: '#ef4444' }
                }
            }
        }
    });
}

function renderCategoryChart(categories) {
    const ctx = document.getElementById("categoryChart");
    if (!ctx) return;

    if (categoryChartInstance) categoryChartInstance.destroy();

    const labels = Object.keys(categories);
    const counts = Object.values(categories);

    categoryChartInstance = new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: labels.map(l => l.toUpperCase()),
            datasets: [{
                data: counts,
                backgroundColor: [
                    '#ef4444', // Security (Red)
                    '#f59e0b', // Resource Leak (Amber)
                    '#00e5ff', // Performance (Cyan)
                    '#8b5cf6', // Null Safety (Violet)
                    '#10b981', // Error Handling (Emerald)
                    '#64748b'  // Others
                ],
                borderWidth: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: { color: '#94a3b8', font: { family: 'Inter', size: 11 }, padding: 12 }
                }
            },
            cutout: '70%'
        }
    });
}

function renderSeverityChart(severities) {
    const ctx = document.getElementById("severityChart");
    if (!ctx) return;

    if (severityChartInstance) severityChartInstance.destroy();

    severityChartInstance = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: ['Critical', 'Warning', 'Suggestion', 'Nitpick'],
            datasets: [{
                label: 'Count',
                data: [
                    severities.critical || 0,
                    severities.warning || 0,
                    severities.suggestion || 0,
                    severities.nitpick || 0
                ],
                backgroundColor: [
                    'rgba(239, 68, 68, 0.85)',
                    'rgba(245, 158, 11, 0.85)',
                    'rgba(0, 229, 255, 0.85)',
                    'rgba(148, 163, 184, 0.5)'
                ],
                borderRadius: 6
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: { legend: { display: false } },
            scales: {
                x: {
                    grid: { display: false },
                    ticks: { color: '#94a3b8' }
                },
                y: {
                    grid: { color: 'rgba(255, 255, 255, 0.05)' },
                    ticks: { color: '#64748b', stepSize: 2 }
                }
            }
        }
    });
}

function renderHotspots(data) {
    const container = document.getElementById("hotspots-container");
    if (!container) return;

    // Aggregate findings count per file
    const fileMap = new Map();
    (data.history || []).forEach(pr => {
        (pr.files || []).forEach(f => {
            const count = fileMap.get(f.path) || 0;
            fileMap.set(f.path, count + (f.findingsCount || 0));
        });
    });

    const sorted = [...fileMap.entries()]
        .filter(([_, count]) => count > 0)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 5);

    container.innerHTML = "";
    if (sorted.length === 0) {
        container.innerHTML = '<div class="text-muted" style="padding: 12px">No hotspots detected. Codebase is clean!</div>';
        return;
    }

    sorted.forEach(([path, count]) => {
        const item = document.createElement("div");
        item.className = "hotspot-item";
        const shortName = path.split("/").pop();
        item.innerHTML = `
            <div class="hotspot-file" title="${path}">
                📄 ${shortName}
                <div style="font-size: 11px; color: var(--text-muted);">${path}</div>
            </div>
            <span class="hotspot-badge">${count} issue${count > 1 ? 's' : ''}</span>
        `;
        container.appendChild(item);
    });
}

function renderHistoryTable(history) {
    const tbody = document.getElementById("history-table-body");
    if (!tbody) return;

    tbody.innerHTML = "";
    if (history.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align: center; color: var(--text-muted);">No review history available.</td></tr>';
        return;
    }

    history.forEach(pr => {
        const tr = document.createElement("tr");
        const dateStr = pr.timestamp ? new Date(pr.timestamp).toLocaleDateString() : "Recent";
        const crit = pr.severities?.critical || 0;
        const warn = pr.severities?.warning || 0;
        const grade = pr.grade || "A";

        tr.innerHTML = `
            <td><strong>#${pr.prNumber}</strong></td>
            <td><strong style="color: var(--accent-primary)">${pr.qualityScore}%</strong></td>
            <td><span class="grade-badge grade-${grade.charAt(0).toLowerCase()}">${grade}</span></td>
            <td>
                <span class="pill pill-critical">🔴 ${crit}</span>
                <span class="pill pill-warning">🟡 ${warn}</span>
            </td>
            <td>${(pr.languages || []).map(l => `<span class="lang-tag">${l}</span>`).join(" ")}</td>
            <td style="color: var(--text-muted)">${dateStr}</td>
            <td>
                <button class="btn-view-pr" onclick="viewPrDetails(${pr.prNumber})">View Breakdown</button>
            </td>
        `;
        tbody.appendChild(tr);
    });
}

function handleSearch(e) {
    const query = e.target.value.toLowerCase();
    if (!currentData || !currentData.history) return;

    const filtered = currentData.history.filter(pr => {
        const prMatch = String(pr.prNumber).includes(query);
        const repoMatch = (pr.repository || "").toLowerCase().includes(query);
        const fileMatch = (pr.files || []).some(f => f.path.toLowerCase().includes(query));
        const langMatch = (pr.languages || []).some(l => l.toLowerCase().includes(query));
        return prMatch || repoMatch || fileMatch || langMatch;
    });

    renderHistoryTable(filtered);
}

function handleFileUpload(e) {
    const file = e.target.files[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
        try {
            const data = JSON.parse(event.target.result);
            // If it's a single PR record, wrap it into a summary structure
            if (data.prNumber && !data.history) {
                currentData = {
                    totalPrsReviewed: 1,
                    averageQualityScore: data.qualityScore || 100,
                    averageGrade: data.grade || "A",
                    totalFilesReviewed: data.filesReviewed || 1,
                    totalBugsPrevented: (data.severities?.critical || 0) + (data.severities?.warning || 0),
                    totalSeverities: data.severities || {},
                    totalCategories: data.categories || {},
                    history: [data]
                };
            } else {
                currentData = data;
            }
            updateStatus(false, `Imported: ${file.name}`);
            renderDashboard(currentData);
        } catch (err) {
            alert("Invalid JSON file format");
        }
    };
    reader.readAsText(file);
}

// Modal Details View
window.viewPrDetails = function(prNumber) {
    if (!currentData || !currentData.history) return;
    const pr = currentData.history.find(p => p.prNumber === prNumber);
    if (!pr) return;

    const modal = document.getElementById("pr-modal");
    const modalTitle = document.getElementById("modal-title");
    const modalGrade = document.getElementById("modal-grade");
    const modalBody = document.getElementById("modal-body");

    modalTitle.textContent = `Pull Request #${pr.prNumber} Review Summary`;
    modalGrade.textContent = `Grade ${pr.grade || 'A'} (${pr.qualityScore}%)`;
    modalGrade.className = "grade-badge grade-" + (pr.grade || 'A').charAt(0).toLowerCase();

    let filesHtml = `
        <div style="margin-bottom: 18px;">
            <div style="font-size: 13px; color: var(--text-secondary); margin-bottom: 6px;">
                Repository: <code>${pr.repository || 'Current Repo'}</code> | Commit: <code>${(pr.headSha || 'head').substring(0, 7)}</code>
            </div>
            <div style="display: flex; gap: 8px; margin-top: 8px;">
                <span class="pill pill-critical">🔴 ${pr.severities?.critical || 0} Critical</span>
                <span class="pill pill-warning">🟡 ${pr.severities?.warning || 0} Warning</span>
                <span class="pill" style="background: rgba(0, 229, 255, 0.15); color: var(--accent-primary)">🔵 ${pr.severities?.suggestion || 0} Suggestion</span>
            </div>
        </div>
        <h4 style="margin-bottom: 12px; font-size: 15px;">Files Analyzed</h4>
    `;

    (pr.files || []).forEach(f => {
        filesHtml += `
            <div class="finding-card ${f.findingsCount > 0 ? 'warning' : 'suggestion'}">
                <div class="finding-header">
                    <span class="finding-file">📄 ${f.path}</span>
                    <span class="lang-tag">${f.language || 'Code'}</span>
                </div>
                <div class="finding-comment">
                    Status: <strong>${f.status}</strong> — ${f.findingsCount || 0} issue(s) reported by AI.
                </div>
            </div>
        `;
    });

    modalBody.innerHTML = filesHtml;
    modal.style.display = "flex";
};

function closeModal() {
    const modal = document.getElementById("pr-modal");
    if (modal) modal.style.display = "none";
}
