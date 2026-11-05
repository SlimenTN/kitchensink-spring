package com.migration.kitchensink.domaine.services;

import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;

import java.util.List;
import java.util.Optional;

public interface Store {

    Optional<Member> findById(long id);

    List<Member> findAllOrderedByName();

    List<Member> findAllOrderedByNameAfterRegistration();

    boolean existsByEmail(String email);

    Member create(NewMember member);
}
