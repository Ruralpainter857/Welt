//! Frontière compacte partagée par les remplissages de fluides et de peintures.
pub(crate) struct Frontier {
    pub width: usize,
    pub seed: usize,
    pub visited_bit: u8,
    pub present_bit: u8,
}

/// Les dimensions et la graine ont été validées par le propriétaire du format.
pub(crate) fn collect(
    spec: Frontier,
    flags: &mut [u8],
    queue: &mut Vec<usize>,
    accept: impl Fn(usize) -> bool,
) {
    queue.clear();
    queue.reserve(flags.len());
    for flag in flags.iter_mut() {
        *flag &= spec.present_bit;
    }
    queue.push(spec.seed);
    flags[spec.seed] |= spec.visited_bit;
    let mut next = 0;
    while next < queue.len() {
        let cell = queue[next];
        next += 1;
        let candidates = [
            (!cell.is_multiple_of(spec.width)).then(|| cell - 1),
            (cell % spec.width + 1 < spec.width).then_some(cell + 1),
            (cell >= spec.width).then(|| cell - spec.width),
            (cell + spec.width < flags.len()).then_some(cell + spec.width),
        ];
        for neighbour in candidates.into_iter().flatten() {
            if flags[neighbour] & spec.visited_bit != 0
                || spec.present_bit != 0 && flags[neighbour] & spec.present_bit == 0
            {
                continue;
            }
            if accept(neighbour) {
                flags[neighbour] |= spec.visited_bit;
                queue.push(neighbour);
            }
        }
    }
}
