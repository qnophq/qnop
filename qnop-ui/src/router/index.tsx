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

import { lazy } from 'react';
import { Navigate, createBrowserRouter } from 'react-router-dom';
import { AppShell } from '../components/shell/AppShell';
import { LazyBoundary } from '../components/errors/LazyBoundary';
import { AdminRoute } from '../components/auth/AdminRoute';
import { ProtectedRoute } from '../components/auth/ProtectedRoute';
import { RoleRoute } from '../components/auth/RoleRoute';
import { AuditPage } from '../pages/audit/AuditPage';
import { HomePage } from '../pages/HomePage';
import { BrandingPage } from '../pages/admin/BrandingPage';
import { StorageConsistencyPage } from '../pages/admin/StorageConsistencyPage';
import { ProfilePage } from '../pages/ProfilePage';
import { UserProfilePage } from '../pages/UserProfilePage';
import { EmailLayout } from '../pages/admin/EmailLayout';
import { EmailServerPage } from '../pages/admin/EmailServerPage';
import { MailTemplatesKeyRedirect } from '../pages/admin/MailTemplatesKeyRedirect';
import { MailTemplatesListPage } from '../pages/admin/MailTemplatesListPage';
import { OidcProvidersPage } from '../pages/admin/OidcProvidersPage';
import { SettingsPage } from '../pages/admin/SettingsPage';
import { ConfigurationPage } from '../pages/admin/ConfigurationPage';
import { SchedulerPage } from '../pages/admin/SchedulerPage';
import { UsersPage } from '../pages/admin/UsersPage';
import { TeamsPage } from '../pages/admin/TeamsPage';
import { TeamDetailPage } from '../pages/admin/TeamDetailPage';
import { SearchPage } from '../pages/search/SearchPage';
import { MyTeamsPage } from '../pages/my-teams/MyTeamsPage';
import { MyTeamDetailPage } from '../pages/my-teams/MyTeamDetailPage';
import { ChangePasswordPage } from '../pages/auth/ChangePasswordPage';
import { ForgotPasswordPage } from '../pages/auth/ForgotPasswordPage';
import { LoginPage } from '../pages/auth/LoginPage';
import { RegisterPage } from '../pages/auth/RegisterPage';
import { ResetPasswordPage } from '../pages/auth/ResetPasswordPage';
import { VerifyEmailPage } from '../pages/auth/VerifyEmailPage';
import { ForbiddenPage } from '../pages/errors/ForbiddenPage';
import { NotFoundPage } from '../pages/errors/NotFoundPage';
import { NewReviewPage } from '../pages/reviews/NewReviewPage';
import { ReviewsPage } from '../pages/reviews/ReviewsPage';
import { ReviewParamGate } from '../components/reviews/ReviewParamGate';

// The template editor pulls in CodeMirror; load it lazily so the rest of the app stays light.
const MailTemplateEditPage = lazy(() =>
  import('../pages/admin/MailTemplateEditPage').then((m) => ({ default: m.MailTemplateEditPage })),
);

// The review surface pulls in pdf.js; load it lazily so the rest of the app stays light (#250).
const DocumentReviewPage = lazy(() =>
  import('../pages/reviews/DocumentReviewPage').then((m) => ({ default: m.DocumentReviewPage })),
);

// The version comparison shares pdf.js with the review surface (#252).
const VersionComparePage = lazy(() =>
  import('../pages/reviews/VersionComparePage').then((m) => ({ default: m.VersionComparePage })),
);
const ReviewTasksPage = lazy(() =>
  import('../pages/reviews/ReviewTasksPage').then((m) => ({ default: m.ReviewTasksPage })),
);

/**
 * Central route table. Public routes (login, errors) sit outside the shell;
 * everything under the shell requires authentication. Surfaces whose screens
 * arrive in later issues render the in-brand placeholder, guarded by the same
 * roles the sidebar uses (#102). Real pages replace these in #104+ / Phase B.
 */
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/verify-email', element: <VerifyEmailPage /> },
  { path: '/change-password', element: <ChangePasswordPage /> },
  { path: '/403', element: <ForbiddenPage /> },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <AppShell />
      </ProtectedRoute>
    ),
    children: [
      { index: true, element: <HomePage /> },
      { path: 'profile', element: <ProfilePage /> },
      { path: 'users/:userId', element: <UserProfilePage /> },
      { path: 'reviews', element: <ReviewsPage /> },
      { path: 'reviews/new', element: <NewReviewPage /> },
      {
        path: 'reviews/:documentId',
        element: (
          <ReviewParamGate>
            <LazyBoundary>
              <DocumentReviewPage />
            </LazyBoundary>
          </ReviewParamGate>
        ),
      },
      {
        path: 'reviews/:documentId/compare',
        element: (
          <ReviewParamGate>
            <LazyBoundary>
              <VersionComparePage />
            </LazyBoundary>
          </ReviewParamGate>
        ),
      },
      {
        path: 'reviews/:documentId/tasks',
        element: (
          <ReviewParamGate>
            <LazyBoundary>
              <ReviewTasksPage />
            </LazyBoundary>
          </ReviewParamGate>
        ),
      },
      { path: 'search', element: <SearchPage /> },
      { path: 'my-teams', element: <MyTeamsPage /> },
      { path: 'my-teams/:id', element: <MyTeamDetailPage /> },
      {
        path: 'audit',
        element: (
          <RoleRoute allow={['ADMIN', 'AUDITOR']}>
            <AuditPage />
          </RoleRoute>
        ),
      },
      {
        path: 'admin/users',
        element: (
          <AdminRoute>
            <UsersPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/teams',
        element: (
          <AdminRoute>
            <TeamsPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/teams/:id',
        element: (
          <AdminRoute>
            <TeamDetailPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/settings',
        element: (
          <AdminRoute>
            <SettingsPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/configuration',
        element: (
          <AdminRoute>
            <ConfigurationPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/scheduler',
        element: (
          <AdminRoute>
            <SchedulerPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/oidc-providers',
        element: (
          <AdminRoute>
            <OidcProvidersPage />
          </AdminRoute>
        ),
      },
      {
        // The email admin area (#525): one tabbed shell over the SMTP
        // server settings and the mail templates, guarded once here.
        path: 'admin/email',
        element: (
          <AdminRoute>
            <EmailLayout />
          </AdminRoute>
        ),
        children: [
          { index: true, element: <Navigate to="/admin/email/server" replace /> },
          { path: 'server', element: <EmailServerPage /> },
          { path: 'templates', element: <MailTemplatesListPage /> },
          {
            path: 'templates/:key',
            element: (
              <LazyBoundary>
                <MailTemplateEditPage />
              </LazyBoundary>
            ),
          },
        ],
      },
      // Pre-#525 bookmarks; targets are guarded, so no AdminRoute here.
      {
        path: 'admin/mail-templates',
        element: <Navigate to="/admin/email/templates" replace />,
      },
      {
        path: 'admin/mail-templates/:key',
        element: <MailTemplatesKeyRedirect />,
      },
      {
        path: 'admin/branding',
        element: (
          <AdminRoute>
            <BrandingPage />
          </AdminRoute>
        ),
      },
      {
        path: 'admin/storage-consistency',
        element: (
          <AdminRoute>
            <StorageConsistencyPage />
          </AdminRoute>
        ),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
