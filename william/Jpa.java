public static Specification<Solution> buildSpecification(SolutionQuery q) {
    return (root, query, cb) -> {

        List<Predicate> predicates = new ArrayList<>();
        query.distinct(true); // 防止 join 导致重复

        // 1️⃣ solutionId
        if (q.getSolutionId() != null) {
            predicates.add(cb.equal(root.get("id"), q.getSolutionId()));
        }

        // 2️⃣ findingName 模糊匹配
        if (q.getFindingName() != null && !q.getFindingName().isEmpty()) {
            predicates.add(cb.like(root.get("findingName"), "%" + q.getFindingName() + "%"));
        }

        // 3️⃣ userId
        if (q.getUserId() != null) {
            predicates.add(cb.equal(root.get("userId"), q.getUserId()));
        }

        // 4️⃣ 创建时间范围
        if (q.getCreatedFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), q.getCreatedFrom()));
        }
        if (q.getCreatedTo() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), q.getCreatedTo()));
        }

        // 5️⃣ verification 三态逻辑
        if (q.getVerificationPassed() == null) {
            // no verification
            predicates.add(cb.isNull(root.get("verification")));
        } else {
            Join<Solution, Verification> v = root.join("verification", JoinType.INNER);
            predicates.add(cb.equal(v.get("passed"), q.getVerificationPassed()));

            // verificationStatus
            if (q.getVerificationStatus() != null) {
                predicates.add(cb.equal(v.get("status"), q.getVerificationStatus()));
            }
        }

        return cb.and(predicates.toArray(new Predicate[0]));
    };
}