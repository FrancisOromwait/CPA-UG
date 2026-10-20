package com.estates.sacco.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
@Entity
@Table(name = "employers")
@Getter @Setter @NoArgsConstructor
public class Employer {



@Id 
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;

@Column(nullable = false, length = 120)
private String name;
private String phone;
private boolean active =true;

    @ManyToOne(optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;     
    }





