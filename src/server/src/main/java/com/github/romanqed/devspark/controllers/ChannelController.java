package com.github.romanqed.devspark.controllers;

import com.github.romanqed.devspark.database.Repository;
import com.github.romanqed.devspark.dto.ChannelDto;
import com.github.romanqed.devspark.dto.DtoUtil;
import com.github.romanqed.devspark.dto.PostDto;
import com.github.romanqed.devspark.javalin.JavalinController;
import com.github.romanqed.devspark.javalin.Route;
import com.github.romanqed.devspark.jwt.JwtUser;
import com.github.romanqed.devspark.models.*;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import javalinjwt.JWTProvider;

import java.util.Date;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@JavalinController("/channel")
public final class ChannelController extends AuthBase {
    private final Repository<Channel> channels;
    private final Repository<Post> posts;
    private final Repository<Comment> comments;

    public ChannelController(JWTProvider<JwtUser> provider,
                             Repository<User> users,
                             Repository<Channel> channels,
                             Repository<Post> posts,
                             Repository<Comment> comments) {
        super(provider, users);
        this.channels = channels;
        this.posts = posts;
        this.comments = comments;
    }

    private static Pattern checkPattern(Context ctx, String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            ctx.status(HttpStatus.BAD_REQUEST);
            return null;
        }
    }

    private Channel seeChannel(Context ctx, User user) {
        var channel = channels.get(ctx.pathParam("channelId"));
        if (channel == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            return null;
        }
        if (user != null && !user.isBanned() && user.hasPermission(Permissions.IGNORE_VISIBILITY)) {
            return channel;
        }
        if (channel.isOwnedBy(user) || channel.getPrivacy() == Privacy.PUBLIC) {
            return channel;
        }
        ctx.status(HttpStatus.NOT_FOUND);
        return null;
    }

    @Route(method = HandlerType.GET, route = "/{channelId}")
    public void get(Context ctx) {
        var user = getUser(ctx);
        var channel = seeChannel(ctx, user);
        if (channel == null) {
            return;
        }
        ctx.json(channel);
    }

    @Route(method = HandlerType.GET)
    public void find(Context ctx) {
        var pagination = DtoUtil.parsePagination(ctx);
        if (pagination == null) {
            return;
        }
        var user = getUser(ctx);
        var all = user != null && !user.isBanned() && user.hasPermission(Permissions.IGNORE_VISIBILITY);
        var name = ctx.queryParam("name");
        if (name != null) {
            var found = Channel.findByName(name, all, channels, pagination);
            ctx.json(found);
            return;
        }
        var raw = ctx.queryParam("pattern");
        if (raw != null) {
            var pattern = checkPattern(ctx, raw);
            if (pattern == null) {
                return;
            }
            var found = Channel.matchByName(pattern, all, channels, pagination);
            ctx.json(found);
            return;
        }
        ctx.json(Channel.findAll(all, channels, pagination));
    }

    @Route(method = HandlerType.GET, route = "/{channelId}/posts")
    public void listPosts(Context ctx) {
        var pagination = DtoUtil.parsePagination(ctx);
        if (pagination == null) {
            return;
        }
        var user = getUser(ctx);
        var channel = seeChannel(ctx, user);
        if (channel == null) {
            return;
        }
        var title = ctx.queryParam("title");
        if (title != null) {
            var found = channel.findPostsByTitle(title, posts, pagination);
            ctx.json(found);
            return;
        }
        var raw = ctx.queryParam("pattern");
        if (raw != null) {
            var pattern = checkPattern(ctx, raw);
            if (pattern == null) {
                return;
            }
            var found = channel.matchPostsByTitle(pattern, posts, pagination);
            ctx.json(found);
            return;
        }
        ctx.json(channel.retrievePosts(posts, pagination));
    }

    @Route(method = HandlerType.PUT)
    public void put(Context ctx) {
        var dto = DtoUtil.validate(ctx, ChannelDto.class);
        if (dto == null) {
            return;
        }
        var user = getCheckedUser(ctx);
        if (user == null) {
            return;
        }
        var channel = Channel.of(user.getId(), dto.getName());
        var privacy = dto.getPrivacy();
        if (privacy != null) {
            channel.setPrivacy(privacy);
        }
        channels.put(channel);
        ctx.json(channel);
    }

    private Channel accessChannel(Context ctx, User user) {
        var ret = channels.get(ctx.pathParam("channelId"));
        if (ret == null) {
            ctx.status(HttpStatus.NOT_FOUND);
            return null;
        }
        if (!ret.isOwnedBy(user) && !user.hasPermission(Permissions.MANAGE_CHANNELS)) {
            ctx.status(HttpStatus.FORBIDDEN);
            return null;
        }
        return ret;
    }

    @Route(method = HandlerType.PUT, route = "/{channelId}/post")
    public void publishPost(Context ctx) {
        var dto = DtoUtil.validate(ctx, PostDto.class);
        if (dto == null) {
            return;
        }
        var user = getCheckedUser(ctx);
        if (user == null) {
            return;
        }
        var channel = accessChannel(ctx, user);
        if (channel == null) {
            return;
        }
        var post = Post.of(user.getId(), channel.getId(), dto.getTitle(), dto.getText());
        post.setTagIds(dto.getTagIds());
        posts.put(post);
        ctx.json(post);
    }

    @Route(method = HandlerType.PATCH, route = "/{channelId}")
    public void update(Context ctx) {
        var dto = DtoUtil.parse(ctx, ChannelDto.class);
        if (dto == null) {
            return;
        }
        var name = dto.getName();
        var privacy = dto.getPrivacy();
        if (name == null && privacy == null) {
            ctx.status(HttpStatus.BAD_REQUEST);
            return;
        }
        var user = getCheckedUser(ctx);
        if (user == null) {
            return;
        }
        var channel = accessChannel(ctx, user);
        if (channel == null) {
            return;
        }
        // Update name
        if (name != null) {
            channel.setName(name);
        }
        // Update privacy
        if (privacy != null) {
            channel.setPrivacy(privacy);
        }
        channel.setUpdated(new Date());
        channels.update(channel.getId(), channel);
    }

    private void deleteChannel(Context ctx, String id) {
        if (!Channel.delete(id, channels, posts, comments)) {
            ctx.status(HttpStatus.NOT_FOUND);
            return;
        }
        ctx.status(HttpStatus.OK);
    }

    @Route(method = HandlerType.DELETE, route = "/{channelId}")
    public void delete(Context ctx) {
        var user = getCheckedUser(ctx);
        if (user == null) {
            return;
        }
        var id = ctx.pathParam("postId");
        if (user.hasPermission(Permissions.MANAGE_CHANNELS)) {
            deleteChannel(ctx, id);
            return;
        }
        if (!Channel.delete(user.getId(), id, channels, posts, comments)) {
            ctx.status(HttpStatus.FORBIDDEN);
            return;
        }
        ctx.status(HttpStatus.OK);
    }
}
