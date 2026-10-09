package com.FGInteractive.BatalhaNaval.auth.model;


import java.time.Instant;
import com.FGInteractive.BatalhaNaval.user.model.User;
import jakarta.persistence.*;
@Entity @Table(name="auth")
public class Auth {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="user_id",nullable=false,unique=true)
    private User user;
    @Column(nullable=false,unique=true,length=254)
    private String email;
    @Column(name="password_hash",nullable=false,length=100)
    private String passwordHash;
    @Column(name="created_at",nullable=false,updatable=false)
    private Instant createdAt;
    protected Auth() {}
    public Auth(User user,String email,String passwordHash,Instant createdAt) {
        this.user=user; this.email=email; this.passwordHash=passwordHash; this.createdAt=createdAt;
    }
    public Long getId(){return id;}
    public User getUser(){return user;}
    public String getEmail(){return email;}
    public String getPasswordHash(){return passwordHash;}
    public void changePasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Instant getCreatedAt(){return createdAt;}
}
