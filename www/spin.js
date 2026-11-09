module.exports = (req, res) => {
    const s = Number(req.query.s || 4);
    const start = Date.now();
    const end = start + s * 1000;
    let iterations = 0;
    while (Date.now() < end) {
        iterations++;
    }
    res.end(`spun ${iterations} iterations in ${s}s\n`);
};
