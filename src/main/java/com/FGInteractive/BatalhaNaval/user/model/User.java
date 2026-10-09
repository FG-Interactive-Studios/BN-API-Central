package com.FGInteractive.BatalhaNaval.user.model;


import java.time.Instant;
import jakarta.persistence.*;
@Entity @Table(name="users")
public class User {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @Column(nullable=false,unique=true,length=30)
    private String nickname;
    @Column(name="avatar_id",nullable=false,length=32)
    private String avatarId = "captain";
    @Column(name="created_at",nullable=false,updatable=false)
    private Instant createdAt;
    protected User() {}
    public User(String nickname,Instant createdAt) { this.nickname=nickname; this.createdAt=createdAt; }
    public Long getId(){return id;}
    public String getNickname(){return nickname;}
    public String getAvatarId(){return avatarId;}
    public void setNickname(String nickname){this.nickname=nickname;}
    public void setAvatarId(String avatarId){this.avatarId=avatarId;}
    public Instant getCreatedAt(){return createdAt;}
}
