const http = require('node:http');
const cluster = require('node:cluster');
const path = require('node:path');
const fs = require('node:fs');

const PORT = parseInt(process.env.PORT, 10) || 18404;
const WORKERS = parseInt(process.env.WORKERS, 10) || 1;
const WWW = process.env.WWW || path.resolve(__dirname, '../../www');

if (WORKERS > 1 && cluster.isPrimary) {
    for (let i = 0; i < WORKERS; i++) {
        cluster.fork();
    }
    cluster.on('exit', () => {
        cluster.fork();
    });
} else {
    const cache = new Map();

    const server = http.createServer(async (req, res) => {
        const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
        const match = url.pathname.match(/^\/([a-zA-Z0-9_-]+)\.js$/);
        if (!match) {
            res.statusCode = 404;
            res.setHeader('Content-Type', 'text/plain');
            res.end('Not Found\n');
            return;
        }

        const name = match[1];
        let handler = cache.get(name);
        if (!handler) {
            const filePath = path.join(WWW, name + '.js');
            if (!fs.existsSync(filePath)) {
                res.statusCode = 404;
                res.setHeader('Content-Type', 'text/plain');
                res.end('Not Found\n');
                return;
            }
            try {
                handler = require(filePath);
                cache.set(name, handler);
            } catch (err) {
                res.statusCode = 500;
                res.setHeader('Content-Type', 'text/plain');
                res.end(err && err.message ? err.message : String(err));
                return;
            }
        }

        const query = {};
        for (const [key, value] of url.searchParams.entries()) {
            query[key] = value;
        }
        req.query = query;

        try {
            await handler(req, res);
        } catch (err) {
            if (!res.headersSent) {
                res.statusCode = 500;
                res.setHeader('Content-Type', 'text/plain');
                res.end(err && err.message ? err.message : String(err));
            }
        }
    });

    server.listen(PORT, () => {
        console.log(`node listening on ${PORT} workers=${WORKERS}`);
    });
}
