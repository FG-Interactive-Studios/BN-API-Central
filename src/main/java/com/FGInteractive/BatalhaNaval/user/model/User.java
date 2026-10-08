package com.FGInteractive.BatalhaNaval.user.model;


import java.time.Instant;
import jakarta.persistence.*;
@Entity @Table(name="users")
public class User {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @Column(nullable=false,unique=true,length=30)
    private String nickname;
    @Column(name="created_at",nullable=false,updatable=false)
    private Instant createdAt;
    protected User() {}
    public User(String nickname,Instant createdAt) { this.nickname=nickname; this.createdAt=createdAt; }
    public Long getId(){return id;}
    public String getNickname(){return nickname;}
    public Instant getCreatedAt(){return createdAt;}
}
