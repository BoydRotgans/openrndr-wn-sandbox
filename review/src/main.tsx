import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { hasAccess } from './lib/access'
import './styles.css'

function Private() {
  return (
    <div className="private">
      <h1>This review is private.</h1>
      <p>Open it with the link you were sent.</p>
    </div>
  )
}

const root = ReactDOM.createRoot(document.getElementById('root')!)
hasAccess('review').then((open) =>
  root.render(
    <React.StrictMode>
      {open ? <App /> : <Private />}
    </React.StrictMode>,
  ),
)
