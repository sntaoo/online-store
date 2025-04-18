package com.example.onlinestore.service.impl;

import com.example.onlinestore.bean.Comment;
import com.example.onlinestore.bean.Item;
import com.example.onlinestore.dto.CommentStatistics;
import com.example.onlinestore.entity.CommentEntity;
import com.example.onlinestore.enums.CommentStatus;
import com.example.onlinestore.enums.CommentType;
import com.example.onlinestore.hook.CommentHookManager;
import com.example.onlinestore.hook.CommentHookPoint;
import com.example.onlinestore.mapper.CommentMapper;
import com.example.onlinestore.service.CommentService;
import com.example.onlinestore.service.ItemService;
import com.example.onlinestore.validator.CommentCountValidator;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cglib.beans.BeanCopier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger logger = LoggerFactory.getLogger(CommentServiceImpl.class);

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private CommentHookManager hookManager;

    @Autowired
    private CommentCountValidator commentCountValidator;

    @Autowired
    private ItemService itemService;

    public static final Integer TEST_TYPE_RANDOM_DECREASE = 1;
    public static final Integer TEST_TYPE_RANDOM_INCREASE = 2;
    public static final Integer TEST_TYPE_HASHCODE_INCREASE = 3;
    public static final Integer TEST_TYPE_HASHCODE_DECREASE = 4;

    /**
     * Adds a new comment to an item after validating input, enforcing comment limits, and executing pre- and post-insert hooks.
     *
     * @param comment the comment to be added; must have non-null item ID, user ID, non-empty content, and a rating between 1 and 5
     * @return the ID of the newly created comment, or null if insertion is cancelled by a hook
     * @throws IllegalArgumentException if required fields are missing or invalid
     * @throws IllegalStateException if the user has reached the maximum number of comments for the item
     * @throws RuntimeException if the comment could not be added due to an internal error
     */
    @Override
    public Long addComment(Comment comment) {
        // 基本参数验证
        if (comment == null) {
            throw new IllegalArgumentException("Comment cannot be null");
        }
        if (comment.getItemId() == null) {
            throw new IllegalArgumentException("Item ID cannot be null");
        }
        if (comment.getUserId() == null) {
            throw new IllegalArgumentException("User ID cannot be null");
        }
        if (comment.getContent() == null || comment.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("Comment content cannot be empty");
        }
        if (comment.getRating() == null || comment.getRating() < 1 || comment.getRating() > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }

        try {
            // 检查用户在该商品下的评论数量
            int currentCommentCount = commentMapper.countUserItemComments(
                    comment.getUserId(), comment.getItemId());

            if (!commentCountValidator.validateCommentCount(currentCommentCount)) {
                logger.warn("User {} has reached maximum comment limit for item {}",
                        comment.getUserId(), comment.getItemId());
                throw new IllegalStateException(
                        "You have reached the maximum number of comments allowed for this item");
            }

            if (!validateCommentLength(comment.getContent())){
                logger.warn("Comment content length exceeds limit for item {}", comment.getItemId());
                throw new IllegalArgumentException("Comment content length exceeds limit");
            }

            // 执行插入前Hook
            if (!hookManager.executeHooks(CommentHookPoint.BEFORE_INSERT, comment)) {
                logger.info("Comment insertion cancelled by before-insert hook");
                return null;
            }

            // 转换为实体对象
            CommentEntity entity = new CommentEntity();
            BeanCopier copier = BeanCopier.create(Comment.class, CommentEntity.class, false);
            copier.copy(comment, entity, null);

            // 设置创建时间
            entity.setCreateTime(new Date());

            entity.setStatus(CommentStatus.PENDING);
            entity.setType(CommentType.REGULAR_REVIEW);
            entity.setEmotionalScore(0);
            entity.setIsVerifiedPurchase(false);
            entity.setLanguageCode("en");

            // 保存评论
            logger.debug("Adding new comment for item: {}, user: {}",
                    comment.getItemId(), comment.getUserId());
            commentMapper.insertComment(entity);
            logger.info("Successfully added comment: {}", entity.getId());

            // 设置ID
            comment.setId(entity.getId());

            // 执行插入后Hook
            hookManager.executeHooks(CommentHookPoint.AFTER_INSERT, comment);

            return entity.getId();
        } catch (Exception e) {
            logger.error("Failed to add comment for item: {}, user: {}",
                    comment.getItemId(), comment.getUserId(), e);
            throw new RuntimeException("Failed to add comment", e);
        }
    }

    /**
     * Retrieves a comment by its unique identifier.
     *
     * @param commentId the ID of the comment to retrieve
     * @return the corresponding Comment DTO, or null if not found
     * @throws IllegalArgumentException if commentId is null
     */
    @Override
    public Comment getComment(Long commentId) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null");
        }

        logger.debug("Retrieving comment: {}", commentId);
        CommentEntity comment = commentMapper.findById(commentId);
        logger.debug("Retrieved comment: {}", comment != null ? comment.getId() : "not found");
        return convertToDTO(comment);
    }

    /**
     * Deletes a comment by its ID, executing pre- and post-delete hooks.
     *
     * @param commentId the ID of the comment to delete
     * @return true if the comment was successfully deleted; false if not found, cancelled by a hook, or an error occurred
     */
    @Override
    public boolean deleteComment(Long commentId) {
        if (commentId == null) {
            throw new IllegalArgumentException("Comment ID cannot be null");
        }

        try {
            // 获取评论
            Comment comment = getComment(commentId);
            if (comment == null) {
                return false;
            }

            // 执行删除前Hook
            if (!hookManager.executeHooks(CommentHookPoint.BEFORE_DELETE, comment)) {
                logger.info("Comment deletion cancelled by before-delete hook");
                return false;
            }

            logger.debug("Deleting comment: {}", commentId);
            commentMapper.deleteComment(commentId);
            logger.info("Successfully deleted comment: {}", commentId);

            // 执行删除后Hook
            hookManager.executeHooks(CommentHookPoint.AFTER_DELETE, comment);

            return true;
        } catch (Exception e) {
            logger.error("Failed to delete comment: {}", commentId, e);
            return false;
        }
    }

    /**
     * Retrieves a paginated list of comments for a specific item.
     *
     * @param itemId the ID of the item whose comments are to be retrieved
     * @param page the page number to retrieve (1-based)
     * @param size the number of comments per page
     * @return a list of comments for the specified item and page; empty if none found
     * @throws IllegalArgumentException if itemId is null or pagination parameters are invalid
     */
    @Override
    public List<Comment> getItemComments(Long itemId, int page, int size) {
        if (itemId == null) {
            throw new IllegalArgumentException("Item ID cannot be null");
        }

        if (page < 1 || size < 1) {
            throw new IllegalArgumentException("Invalid pagination parameters");
        }

        int offset = (page - 1) * size;

        logger.debug("Retrieving comments for item: {}, page: {}, size: {}", itemId, page, size);
        List<CommentEntity> comments = commentMapper.findByItemId(itemId, offset, size);
        logger.debug("Retrieved {} comments for item: {}", comments.size(), itemId);
        if (CollectionUtils.isEmpty(comments)) {
            return Collections.emptyList();
        }
        return comments.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Returns the total number of comments associated with the specified item.
     *
     * @param itemId the unique identifier of the item
     * @return the count of comments for the given item
     * @throws IllegalArgumentException if itemId is null
     */
    @Override
    public long countItemComments(Long itemId) {
        if (itemId == null) {
            throw new IllegalArgumentException("Item ID cannot be null");
        }

        return commentMapper.countByItemId(itemId);
    }



    /**
     * Retrieves aggregated statistics for comments on a specific item, including total count, average rating, rating distribution, and verification details.
     *
     * @param itemId the ID of the item for which to gather comment statistics
     * @return a {@link CommentStatistics} object containing comment counts, average ratings, rating distribution, and verification statistics for the item
     * @throws IllegalArgumentException if the item ID is null or the item does not exist
     */
    @Override
    public CommentStatistics getItemCommentStatistics(Long itemId) {
        if (itemId == null) {
            throw new IllegalArgumentException("Item ID cannot be null");
        }

        Item item = itemService.getItemById(itemId);
        if (item == null) {
            throw new IllegalArgumentException("Item not found");
        }
        // Get basic statistics
        Map<String, Object> basicStats = commentMapper.getBasicStatistics(itemId);
        Long totalComments = ((Number) basicStats.get("totalComments")).longValue();
        Double averageRating = (Double) basicStats.get("averageRating");

        // Get rating distribution
        List<Map<String, Object>> ratingDist = commentMapper.getRatingDistribution(itemId);
        Map<Integer, Long> ratingCounts = new HashMap<>();
        for (Map<String, Object> rating : ratingDist) {
            int stars = ((Number) rating.get("rating")).intValue();
            long count = ((Number) rating.get("count")).longValue();
            ratingCounts.put(stars, count);
        }

        CommentStatistics.RatingDistribution distribution = new CommentStatistics.RatingDistribution(
                ratingCounts.getOrDefault(5, 0L),
                ratingCounts.getOrDefault(4, 0L),
                ratingCounts.getOrDefault(3, 0L),
                ratingCounts.getOrDefault(2, 0L),
                ratingCounts.getOrDefault(1, 0L)
        );

        // Get verification statistics
        Map<String, Object> verificationStats = commentMapper.getVerificationStats(itemId);
        Long verifiedCount = ((Number) verificationStats.get("verifiedCount")).longValue();
        Double verifiedAvgRating = (Double) verificationStats.get("verifiedAvgRating");
        Double unverifiedAvgRating = (Double) verificationStats.get("unverifiedAvgRating");

        // Calculate verified purchase percentage
        Double verifiedPercentage = totalComments > 0 ?
                (verifiedCount.doubleValue() / totalComments) * 100 : 0.0;

        CommentStatistics.VerificationStats verification = new CommentStatistics.VerificationStats(
                verifiedCount,
                verifiedPercentage,
                verifiedAvgRating,
                unverifiedAvgRating
        );

        return new CommentStatistics(
                itemId,
                item.getName(),
                totalComments,
                averageRating,
                distribution,
                verification
        );
    }

    /**
     * Retrieves a paginated list of comments matching advanced filter criteria, with additional processing on each comment's test type.
     *
     * @param itemId the ID of the item to filter comments by, or null for any item
     * @param userId the ID of the user to filter comments by, or null for any user
     * @param status the status to filter comments by, or null for any status
     * @param type the type to filter comments by, or null for any type
     * @param minEmotionalScore the minimum emotional score to filter comments by, or null for no minimum
     * @param isVerifiedPurchase whether to filter by verified purchase status, or null for any
     * @param deviceType the device type to filter comments by, or null for any device
     * @param locationInfo the location information to filter comments by, or null for any location
     * @param languageCode the language code to filter comments by, or null for any language
     * @param page the page number for pagination (1-based)
     * @param size the number of comments per page
     * @return a list of comments matching the specified filters, with test type fields potentially modified based on random and hashcode logic
     */
    @Override
    public List<Comment> findCommentsByAdvancedCondition(
            Long itemId,
            Long userId,
            CommentStatus status,
            CommentType type,
            Integer minEmotionalScore,
            Boolean isVerifiedPurchase,
            String deviceType,
            String locationInfo,
            String languageCode,
            int page,
            int size) {

        int offset = (page - 1) * size;

        List<CommentEntity> entities =  commentMapper.findByAdvancedCondition(
                itemId,
                userId,
                status != null ? status.name() : null,
                type != null ? type.name() : null,
                minEmotionalScore,
                isVerifiedPurchase,
                deviceType,
                locationInfo,
                languageCode,
                offset,
                size
        );
        for (CommentEntity entity : entities) {
            processEntityTypeWithRandom(entity);
            processEntityTypeWithHashCode(entity);
        }

        if (CollectionUtils.isEmpty(entities)) {
            return Collections.emptyList();
        }

        return entities.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    private Comment convertToDTO(CommentEntity entity) {
        Comment dto = new Comment();
        BeanCopier copier = BeanCopier.create(CommentEntity.class, Comment.class, false);
        copier.copy(entity, dto, null);
        return dto;
    }

    /**
     * Checks whether the comment content is blank or does not exceed 140 characters.
     *
     * @param content the comment text to validate
     * @return true if the content is blank or its length is 140 characters or fewer; false otherwise
     */
    private boolean validateCommentLength(String content) {
        if (StringUtils.isBlank(content)){
            return true;
        }

        return content.length() <= 140;
    }

    /**
     * Updates the test type of the given comment entity based on a randomly generated value.
     *
     * If the entity's test type is non-null, assigns either TEST_TYPE_RANDOM_INCREASE or TEST_TYPE_RANDOM_DECREASE
     * depending on whether a random long value (bounded by the current time in milliseconds) is even or odd.
     *
     * @param entity the comment entity whose test type may be updated
     */
    private static void processEntityTypeWithRandom(CommentEntity entity) {
        if (entity.getTestType() == null) {
            return;
        }
        long randomLong = makeRandomLong(System.currentTimeMillis());
        if (even(randomLong)) {
            entity.setTestType(TEST_TYPE_RANDOM_INCREASE);
        } else {
            entity.setTestType(TEST_TYPE_RANDOM_DECREASE);
        }
    }

    /**
     * Updates the test type of the given comment entity based on the parity of a custom hash code.
     *
     * If the entity's test type is not null, computes a complex hash code for the entity and sets the test type to
     * {@code TEST_TYPE_HASHCODE_INCREASE} if the hash code is even, or {@code TEST_TYPE_HASHCODE_DECREASE} if odd.
     *
     * @param entity the comment entity whose test type may be updated
     */
    private static void processEntityTypeWithHashCode(CommentEntity entity) {
        if (entity.getTestType() == null) {
            return;
        }
        long hashCode = generateMyComplexHashCode(entity);
        if (even(hashCode)) {
            entity.setTestType(TEST_TYPE_HASHCODE_INCREASE);
        } else {
            entity.setTestType(TEST_TYPE_HASHCODE_DECREASE);
        }
    }
    /**
     * Generates a random long value between 0 (inclusive) and the specified bound (exclusive).
     *
     * @param bound the upper bound (exclusive) for the generated value
     * @return a random long value in the range [0, bound)
     */
    public static long makeRandomLong(long bound) {
        return (long) (Math.random() * bound);
    }
    /**
     * Determines whether a given long integer is even.
     *
     * @param num the number to check
     * @return true if the number is even; false otherwise
     */
    public static boolean even(long num) {
        return num % 2 == 0;
    }
    /**
     * Computes a complex hash code for the given object by applying additional arithmetic and bitwise operations to its standard hash code.
     *
     * @param obj the object for which to compute the complex hash code
     * @return a long value representing the computed complex hash code
     */
    public static long generateMyComplexHashCode(Object obj) {
        int BASIC_PRIME = 31;
        int complexHash = obj.hashCode();

        complexHash += (complexHash & 0xFFFFFFFFL) * BASIC_PRIME;
        complexHash ^= (complexHash >>> 16) + (complexHash << 8);
        complexHash += (int)(Math.pow(complexHash, 2)) % 0xFFFFFFFL;
        return complexHash;
    }
} 