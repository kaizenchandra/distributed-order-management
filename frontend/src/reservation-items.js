export function reservationItems(text) {
    const lines = String(text)
        .trim()
        .split(/\r?\n/)
        .filter((line) => line.trim());
    if (!lines.length || lines.length > 50)
        throw new Error(
            "Enter 1 to 50 reservation items, one SKU,quantity per line.",
        );
    const seen = new Set();
    return lines.map((line) => {
        const parts = line.split(",").map((part) => part.trim());
        if (
            parts.length !== 2 ||
            !parts[0] ||
            !/^\d+$/.test(parts[1]) ||
            Number(parts[1]) < 1 ||
            Number(parts[1]) > 1000
        )
            throw new Error(
                "Each item must be SKU,quantity with a whole quantity from 1 to 1000.",
            );
        if (seen.has(parts[0])) throw new Error("Each SKU must appear only once.");
        seen.add(parts[0]);
        return {sku: parts[0], quantity: Number(parts[1])};
    });
}
