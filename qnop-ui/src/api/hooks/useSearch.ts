/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type {
  DiscussionSearchPage,
  GlobalSearchResponse,
  ReviewSearchPage,
  TeamSearchPage,
  UserSearchPage,
} from '../generated';
import { searchApi } from '../config';

/** Queries below this length answer empty server-side — don't even ask (issue #540). */
export const SEARCH_MIN_LENGTH = 2;

export const searchKeys = {
  all: ['search'] as const,
  quick: (q: string) => [...searchKeys.all, 'quick', q] as const,
  reviews: (q: string, page: number) => [...searchKeys.all, 'reviews', q, page] as const,
  annotations: (q: string, page: number) => [...searchKeys.all, 'annotations', q, page] as const,
  comments: (q: string, page: number) => [...searchKeys.all, 'comments', q, page] as const,
  users: (q: string, page: number) => [...searchKeys.all, 'users', q, page] as const,
  teams: (q: string, page: number) => [...searchKeys.all, 'teams', q, page] as const,
};

const searchable = (q: string) => q.trim().length >= SEARCH_MIN_LENGTH;

/**
 * The grouped top hits behind the top-bar dropdown (issue #540). Enabled only
 * from the server's minimum query length; previous data is kept so the open
 * dropdown never flashes empty between keystrokes.
 */
export function useSearchQuick(q: string) {
  return useQuery<GlobalSearchResponse>({
    queryKey: searchKeys.quick(q),
    queryFn: async () => (await searchApi.searchQuick({ q })).data,
    enabled: searchable(q),
    placeholderData: keepPreviousData,
  });
}

/** One page of review hits for the results page. */
export function useSearchReviews(q: string, page: number, enabled = true) {
  return useQuery<ReviewSearchPage>({
    queryKey: searchKeys.reviews(q, page),
    queryFn: async () => (await searchApi.searchReviews({ q, page })).data,
    enabled: enabled && searchable(q),
    placeholderData: keepPreviousData,
  });
}

/** One page of annotation (thread-opener) hits for the results page. */
export function useSearchAnnotations(q: string, page: number, enabled = true) {
  return useQuery<DiscussionSearchPage>({
    queryKey: searchKeys.annotations(q, page),
    queryFn: async () => (await searchApi.searchAnnotations({ q, page })).data,
    enabled: enabled && searchable(q),
    placeholderData: keepPreviousData,
  });
}

/** One page of comment (reply) hits for the results page. */
export function useSearchComments(q: string, page: number, enabled = true) {
  return useQuery<DiscussionSearchPage>({
    queryKey: searchKeys.comments(q, page),
    queryFn: async () => (await searchApi.searchComments({ q, page })).data,
    enabled: enabled && searchable(q),
    placeholderData: keepPreviousData,
  });
}

/** One page of people hits for the results page. */
export function useSearchUsers(q: string, page: number, enabled = true) {
  return useQuery<UserSearchPage>({
    queryKey: searchKeys.users(q, page),
    queryFn: async () => (await searchApi.searchUsers({ q, page })).data,
    enabled: enabled && searchable(q),
    placeholderData: keepPreviousData,
  });
}

/** One page of team hits for the results page. */
export function useSearchTeams(q: string, page: number, enabled = true) {
  return useQuery<TeamSearchPage>({
    queryKey: searchKeys.teams(q, page),
    queryFn: async () => (await searchApi.searchTeams({ q, page })).data,
    enabled: enabled && searchable(q),
    placeholderData: keepPreviousData,
  });
}
