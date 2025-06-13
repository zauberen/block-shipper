// Sample data for 24-hour charts
const hours = Array.from({length: 24}, (_, i) => `${i}:00`);

const chartOptions = {
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
        legend: {
            display: false
        }
    },
    scales: {
        x: {
            grid: {
                color: '#333333',
                borderColor: '#333333'
            },
            ticks: {
                color: '#808080',
                maxTicksLimit: 6
            }
        },
        y: {
            grid: {
                color: '#333333',
                borderColor: '#333333'
            },
            ticks: {
                color: '#808080'
            }
        }
    }
};

// Web Server Chart
new Chart(document.getElementById('webServerChart'), {
    type: 'line',
    data: {
        labels: hours,
        datasets: [{
            data: [45, 52, 48, 61, 78, 94, 112, 134, 189, 223, 267, 312, 289, 345, 398, 412, 387, 356, 298, 234, 187, 143, 98, 67],
            borderColor: '#4ade80',
            backgroundColor: 'rgba(74, 222, 128, 0.1)',
            fill: true,
            tension: 0.4
        }]
    },
    options: chartOptions
});

// Database Chart
new Chart(document.getElementById('databaseChart'), {
    type: 'line',
    data: {
        labels: hours,
        datasets: [{
            data: [23, 28, 31, 35, 42, 48, 56, 67, 78, 89, 95, 103, 98, 112, 126, 134, 142, 156, 148, 139, 128, 98, 76, 54],
            borderColor: '#4ade80',
            backgroundColor: 'rgba(74, 222, 128, 0.1)',
            fill: true,
            tension: 0.4
        }]
    },
    options: chartOptions
});

// API Gateway Chart
new Chart(document.getElementById('apiChart'), {
    type: 'line',
    data: {
        labels: hours,
        datasets: [{
            data: [567, 623, 789, 945, 1123, 1456, 1789, 2001, 2234, 2456, 2678, 2789, 2591, 2734, 2845, 2891, 2756, 2634, 2345, 1987, 1678, 1234, 987, 734],
            borderColor: '#4ade80',
            backgroundColor: 'rgba(74, 222, 128, 0.1)',
            fill: true,
            tension: 0.4
        }]
    },
    options: chartOptions
});

// Cache Server Chart (offline - showing last known data)
new Chart(document.getElementById('cacheChart'), {
    type: 'line',
    data: {
        labels: hours.slice(0, 22), // Only show data up to 2 hours ago
        datasets: [{
            data: [12, 15, 18, 22, 26, 31, 35, 42, 48, 54, 61, 67, 73, 78, 84, 89, 93, 97, 102, 98, 89, 76],
            borderColor: '#ef4444',
            backgroundColor: 'rgba(239, 68, 68, 0.1)',
            fill: true,
            tension: 0.4
        }]
    },
    options: {
        ...chartOptions,
        plugins: {
            ...chartOptions.plugins,
            title: {
                display: true,
                text: 'Offline - Last 22 hours',
                color: '#ef4444',
                font: {
                    size: 12
                }
            }
        }
    }
});
